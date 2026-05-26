package org.qortium.network.task;

import org.qortium.controller.Controller;
import org.qortium.utils.ExecuteProduceConsume.Task;

public class BroadcastTask implements Task {
    public BroadcastTask() {
    }

    @Override
    public String getName() {
        return "BroadcastTask";
    }

    @Override
    public void perform() throws InterruptedException {
        Controller.getInstance().doNetworkBroadcast();
    }
}
