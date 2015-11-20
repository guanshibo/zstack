package org.zstack.storage.primary.local;

import org.zstack.header.message.APIParam;
import org.zstack.header.message.APISyncCallMessage;
import org.zstack.header.rest.APINoSee;
import org.zstack.header.storage.primary.PrimaryStorageMessage;
import org.zstack.header.volume.VolumeVO;

/**
 * Created by frank on 11/18/2015.
 */
public class APILocalStorageGetVolumeMigratableHostsMsg extends APISyncCallMessage implements PrimaryStorageMessage {
    @APIParam(resourceType = VolumeVO.class, checkAccount = true, operationTarget = true)
    private String volumeUuid;
    @APINoSee
    private String primaryStorageUuid;

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    @Override
    public String getPrimaryStorageUuid() {
        return primaryStorageUuid;
    }

    public void setPrimaryStorageUuid(String primaryStorageUuid) {
        this.primaryStorageUuid = primaryStorageUuid;
    }
}