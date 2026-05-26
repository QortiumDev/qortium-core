package org.qortium.controller.hsqldb;

import org.qortium.data.arbitrary.ArbitraryResourceCache;
import org.qortium.repository.RepositoryManager;
import org.qortium.repository.hsqldb.HSQLDBCacheUtils;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.settings.Settings;

public class HSQLDBDataCacheManager extends Thread{

    public HSQLDBDataCacheManager() {}

    @Override
    public void run() {
        Thread.currentThread().setName("HSQLDB Data Cache Manager");

        HSQLDBCacheUtils.startCaching(
            Settings.getInstance().getDbCacheThreadPriority(),
            Settings.getInstance().getDbCacheFrequency()
        );
    }
}
