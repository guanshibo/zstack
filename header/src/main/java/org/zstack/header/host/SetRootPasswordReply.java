package org.zstack.header.host;

import org.zstack.header.message.MessageReply;
import org.zstack.header.vm.VmAccountPerference;

/**
 * Created by mingjian.deng on 16/10/26.
 */
public class SetRootPasswordReply extends MessageReply {
    private VmAccountPerference vmAccountPerference;
    private String qcowFile;

    public VmAccountPerference getVmAccountPerference() { return vmAccountPerference; }
    public void setVmAccountPerference(VmAccountPerference vmAccountPerference) { this.vmAccountPerference = vmAccountPerference; }

    public String getQcowFile() {
        return qcowFile;
    }
    public void setQcowFile(String qcowFile) {
        this.qcowFile = qcowFile;
    }
}
