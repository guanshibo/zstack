package org.zstack.storage.primary.local;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.host.HostConstant;
import org.zstack.header.host.MigrateVmOnHypervisorMsg;
import org.zstack.header.message.MessageReply;
import org.zstack.header.storage.primary.PrimaryStorageConstant;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.volume.VolumeInventory;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by frank on 10/24/2015.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class LocalStorageMigrateVmFlow extends NoRollbackFlow {
    private static final CLogger logger = Utils.getLogger(LocalStorageMigrateVmFlow.class);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private CloudBus bus;

    @Override
    public void run(final FlowTrigger next, Map data) {
        final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        final String srcHostUuid = spec.getVmInventory().getHostUuid();
        final String dstHostUuid = spec.getDestHost().getUuid();

        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.EQ, spec.getVmInventory().getRootVolumeUuid());
        final LocalStorageResourceRefVO ref = q.find();

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("migrate-vm-%s-on-localstorage-%s", spec.getVmInventory().getUuid(), ref.getPrimaryStorageUuid()));
        chain.then(new ShareFlow() {
            long volumeSize = 0;

            {
                for (VolumeInventory vol : spec.getVmInventory().getAllVolumes()) {
                    volumeSize += vol.getSize();
                }
            }

            @Override
            public void setup() {
                flow(new Flow() {
                    String __name__ = "reserve-capacity-on-host";

                    boolean s = false;

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        LocalStorageReserveHostCapacityMsg msg = new LocalStorageReserveHostCapacityMsg();
                        msg.setHostUuid(dstHostUuid);
                        msg.setSize(volumeSize);
                        msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                        bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                        bus.send(msg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                    return;
                                }

                                s = true;
                                trigger.next();
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowTrigger trigger, Map data) {
                        if (s) {
                            LocalStorageReturnHostCapacityMsg msg = new LocalStorageReturnHostCapacityMsg();
                            msg.setHostUuid(dstHostUuid);
                            msg.setSize(volumeSize);
                            msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                            bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                            bus.send(msg);
                        }

                        trigger.rollback();
                    }
                });

                flow(new Flow() {
                    String __name__ = "create-volumes-on-dst-host";

                    List<VolumeInventory> successVolumes = new ArrayList<VolumeInventory>();

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        List<LocalStorageCreateEmptyVolumeMsg> msgs = CollectionUtils.transformToList(spec.getVmInventory().getAllVolumes(), new Function<LocalStorageCreateEmptyVolumeMsg, VolumeInventory>() {
                            @Override
                            public LocalStorageCreateEmptyVolumeMsg call(VolumeInventory arg) {
                                LocalStorageCreateEmptyVolumeMsg msg = new LocalStorageCreateEmptyVolumeMsg();
                                msg.setHostUuid(dstHostUuid);
                                msg.setVolume(arg);
                                msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                                bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                                return msg;
                            }
                        });

                        bus.send(msgs, 1, new CloudBusListCallBack(trigger) {
                            @Override
                            public void run(List<MessageReply> replies) {
                                for (MessageReply r : replies) {
                                    if (!r.isSuccess()) {
                                        trigger.fail(r.getError());
                                        return;
                                    }

                                    successVolumes.add(spec.getVmInventory().getAllVolumes().get(replies.indexOf(r)));
                                }

                                trigger.next();
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowTrigger trigger, Map data) {
                        if (!successVolumes.isEmpty()) {
                            List<LocalStorageDirectlyDeleteVolumeMsg> msgs = CollectionUtils.transformToList(successVolumes, new Function<LocalStorageDirectlyDeleteVolumeMsg, VolumeInventory>() {
                                @Override
                                public LocalStorageDirectlyDeleteVolumeMsg call(VolumeInventory arg) {
                                    LocalStorageDirectlyDeleteVolumeMsg msg = new LocalStorageDirectlyDeleteVolumeMsg();
                                    msg.setHostUuid(dstHostUuid);
                                    msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                                    msg.setVolume(arg);
                                    bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                                    return msg;
                                }
                            });

                            bus.send(msgs);
                        }

                        trigger.rollback();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "migrate-vm";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        MigrateVmOnHypervisorMsg msg = new MigrateVmOnHypervisorMsg();
                        msg.setVmInventory(spec.getVmInventory());
                        msg.setDestHostInventory(spec.getDestHost());
                        msg.setSrcHostUuid(spec.getVmInventory().getHostUuid());
                        msg.setWithStorage(true);
                        bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, spec.getDestHost().getUuid());
                        bus.send(msg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (reply.isSuccess()) {
                                    trigger.next();
                                } else {
                                    trigger.fail(reply.getError());
                                }
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "update-volumes-info-in-db-to-dst-host";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        List<String> volUuids = CollectionUtils.transformToList(spec.getVmInventory().getAllVolumes(), new Function<String, VolumeInventory>() {
                            @Override
                            public String call(VolumeInventory arg) {
                                return arg.getUuid();
                            }
                        });

                        SimpleQuery<LocalStorageResourceRefVO> q = dbf.createQuery(LocalStorageResourceRefVO.class);
                        q.add(LocalStorageResourceRefVO_.resourceUuid, Op.IN, volUuids);
                        List<LocalStorageResourceRefVO> refs = q.list();
                        for (LocalStorageResourceRefVO ref : refs) {
                            ref.setHostUuid(dstHostUuid);
                        }

                        dbf.updateCollection(refs);
                        trigger.next();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "delete-volumes-on-src-host";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        List<LocalStorageDirectlyDeleteVolumeMsg> msgs = CollectionUtils.transformToList(spec.getVmInventory().getAllVolumes(), new Function<LocalStorageDirectlyDeleteVolumeMsg, VolumeInventory>() {
                            @Override
                            public LocalStorageDirectlyDeleteVolumeMsg call(VolumeInventory arg) {
                                LocalStorageDirectlyDeleteVolumeMsg msg = new LocalStorageDirectlyDeleteVolumeMsg();
                                msg.setHostUuid(srcHostUuid);
                                msg.setVolume(arg);
                                msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                                bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                                return msg;
                            }
                        });

                        bus.send(msgs, new CloudBusListCallBack() {
                            @Override
                            public void run(List<MessageReply> replies) {
                                for (MessageReply r : replies) {
                                    if (!r.isSuccess()) {
                                        //TODO:
                                        VolumeInventory vol = spec.getVmInventory().getAllVolumes().get(replies.indexOf(r));
                                        logger.warn(String.format("failed to delete the volume[%s] in the host[uuid:%s] for the local" +
                                                        " primary storage[uuid:%s] during after the vm[uuid:%s] migrated to the host[uuid:%s, ip:%s], %s",
                                                vol.getUuid(), srcHostUuid, ref.getPrimaryStorageUuid(), spec.getVmInventory().getUuid(), dstHostUuid,
                                                spec.getDestHost().getManagementIp(), r.getError()));
                                    }
                                }
                            }
                        });

                        trigger.next();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "return-capacity-to-src-host";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        LocalStorageReturnHostCapacityMsg msg = new LocalStorageReturnHostCapacityMsg();
                        msg.setHostUuid(srcHostUuid);
                        msg.setPrimaryStorageUuid(ref.getPrimaryStorageUuid());
                        msg.setSize(volumeSize);
                        bus.makeTargetServiceIdByResourceUuid(msg, PrimaryStorageConstant.SERVICE_ID, ref.getPrimaryStorageUuid());
                        bus.send(msg, new CloudBusCallBack() {
                            @Override
                            public void run(MessageReply reply) {
                                //TODO
                                if (!reply.isSuccess()) {
                                    logger.warn(String.format("failed to return capacity[%s] to the host[uuid:%s] of the local" +
                                            " primary storage[uuid:%s], %s", volumeSize, srcHostUuid, ref.getPrimaryStorageUuid(), reply.getError()));
                                }
                            }
                        });

                        trigger.next();
                    }
                });

                done(new FlowDoneHandler(next) {
                    @Override
                    public void handle(Map data) {
                        next.next();
                    }
                });

                error(new FlowErrorHandler(next) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        next.fail(errCode);
                    }
                });
            }
        }).start();
    }
}