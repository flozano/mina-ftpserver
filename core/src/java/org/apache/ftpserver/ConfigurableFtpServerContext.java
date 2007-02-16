/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */  

package org.apache.ftpserver;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ftpserver.filesystem.NativeFileSystemManager;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.Configuration;
import org.apache.ftpserver.ftplet.DefaultFtpletContainer;
import org.apache.ftpserver.ftplet.FileSystemManager;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpStatistics;
import org.apache.ftpserver.ftplet.Ftplet;
import org.apache.ftpserver.ftplet.FtpletContainer;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.interfaces.CommandFactory;
import org.apache.ftpserver.interfaces.FtpServerContext;
import org.apache.ftpserver.interfaces.IpRestrictor;
import org.apache.ftpserver.interfaces.MessageResource;
import org.apache.ftpserver.iprestrictor.FileIpRestrictor;
import org.apache.ftpserver.listener.ConnectionManager;
import org.apache.ftpserver.listener.ConnectionManagerImpl;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.mina.MinaListener;
import org.apache.ftpserver.message.MessageResourceImpl;
import org.apache.ftpserver.usermanager.BaseUser;
import org.apache.ftpserver.usermanager.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.PropertiesUserManager;
import org.apache.ftpserver.usermanager.TransferRatePermission;
import org.apache.ftpserver.usermanager.WritePermission;

/**
 * FTP server configuration implementation. It holds all 
 * the components used.
 */
public class ConfigurableFtpServerContext implements FtpServerContext {

    private LogFactory logFactory;
    private Bean messageResourceBean;
    private Bean connectionManagerBean;
    private Bean ipRestrictorBean;
    private Bean userManagerBean;
    private Bean fileSystemManagerBean;
    private Bean ftpletContainerBean;
    private Bean statisticsBean;
    private Bean commandFactoryBean;
    
    private Log log;
    private Map listeners = new HashMap();
    
    private static final Authority[] ADMIN_AUTHORITIES = new Authority[]{
        new WritePermission()
    };

    private static final Authority[] ANON_AUTHORITIES = new Authority[]{
        new ConcurrentLoginPermission(20, 2),
        new TransferRatePermission(4800, 4800)
    };
    
    /**
     * Constructor - set the root configuration.
     */
    public ConfigurableFtpServerContext(Configuration conf) throws Exception {
        
        try {
            
            // get the log classes
            logFactory = LogFactory.getFactory();
            logFactory = new FtpLogFactory(logFactory);
            log        = logFactory.getInstance(ConfigurableFtpServerContext.class);
            
            listeners = createListeners(conf, "listeners");
            
            // create all the components
            messageResourceBean   = createComponent(conf, "message",             MessageResourceImpl.class.getName());
            connectionManagerBean = createComponent(conf, "connection-manager",  ConnectionManagerImpl.class.getName());
            ipRestrictorBean      = createComponent(conf, "ip-restrictor",       FileIpRestrictor.class.getName());
            userManagerBean       = createComponent(conf, "user-manager",        PropertiesUserManager.class.getName());
            fileSystemManagerBean = createComponent(conf, "file-system-manager", NativeFileSystemManager.class.getName());
            statisticsBean        = createComponent(conf, "statistics",          FtpStatisticsImpl.class.getName());
            commandFactoryBean    = createComponent(conf, "command-factory",     DefaultCommandFactory.class.getName());
            
            // create user if necessary
            boolean userCreate = conf.getBoolean("create-default-user", true);
            if(userCreate) {
                createDefaultUsers();
            }
            
            ftpletContainerBean    = createComponent(conf, "ftplet-container",     DefaultFtpletContainer.class.getName());
       
            initFtplets((FtpletContainer) ftpletContainerBean.getBean(), conf);
        }
        catch(Exception ex) {
            dispose();
            throw ex;
        }
    }
    
    private Map createListeners(Configuration conf, String prefix) throws Exception {
        Map map = new HashMap();

        Configuration listenersConfig = conf.subset(prefix);
        if(listenersConfig.isEmpty()) {
            // create default listener
            Bean listenerBean = createComponent(listenersConfig, "default", MinaListener.class.getName());
            
            map.put("default", listenerBean);
        } else {
        
            Iterator keys = listenersConfig.getKeys();
            
            while (keys.hasNext()) {
                String key = (String) keys.next();
                
                Bean listenerBean = createComponent(listenersConfig, key, MinaListener.class.getName());
                
                map.put(key, listenerBean);
            }
        }
        
       
        return map;
    }

    /**
     * create and initialize ftlets
     * @param container
     * @param conf
     * @throws FtpException
     */
    private void initFtplets(FtpletContainer container, Configuration conf) throws FtpException {
        String ftpletNames = conf.getString("ftplets", null);
        Configuration ftpletConf = conf.subset("ftplet");
                
        if(ftpletNames == null) {
            return;
        }
        
        //log = ftpConfig.getLogFactory().getInstance(getClass());
        StringTokenizer st = new StringTokenizer(ftpletNames, " ,;\r\n\t");
        try {
            while(st.hasMoreTokens()) {
                String ftpletName = st.nextToken();
                log.info("Configuring ftplet : " + ftpletName);
                
                // get ftplet specific configuration
                Configuration subConfig = ftpletConf.subset(ftpletName);
                String className = subConfig.getString("class", null);
                if(className == null) {
                    continue;
                }
                Ftplet ftplet = (Ftplet)Class.forName(className).newInstance();
                ftplet.init(this, subConfig);
                container.addFtplet(ftpletName, ftplet);
            }
        }
        catch(FtpException ex) {
            container.destroy();
            throw ex;
        }
        catch(Exception ex) {
            container.destroy();
            log.fatal("FtpletContainer.init()", ex);
            throw new FtpException("FtpletContainer.init()", ex);
        }
    }
    
    /**
     * Create component. 
     */
    private Bean createComponent(Configuration parentConfig, String configName, String defaultClass) throws Exception {
        
        // get configuration subset
        Configuration conf = parentConfig.subset(configName);
        
        Bean bean = Bean.createBean(conf, defaultClass, logFactory);
        bean.initBean();
        return bean;
    }
    
    /**
     * Create default users.
     */
    private void createDefaultUsers() throws Exception {
        UserManager userManager = getUserManager();
        
        // create admin user
        String adminName = userManager.getAdminName();
        if(!userManager.doesExist(adminName)) {
            log.info("Creating user : " + adminName);
            BaseUser adminUser = new BaseUser();
            adminUser.setName(adminName);
            adminUser.setPassword(adminName);
            adminUser.setEnabled(true);
            
            adminUser.setAuthorities(ADMIN_AUTHORITIES);

            adminUser.setHomeDirectory("./res/home");
            adminUser.setMaxIdleTime(0);
            userManager.save(adminUser);
        }
        
        // create anonymous user
        if(!userManager.doesExist("anonymous")) {
            log.info("Creating user : anonymous");
            BaseUser anonUser = new BaseUser();
            anonUser.setName("anonymous");
            anonUser.setPassword("");
            
            anonUser.setAuthorities(ANON_AUTHORITIES);
            
            anonUser.setEnabled(true);

            anonUser.setHomeDirectory("./res/home");
            anonUser.setMaxIdleTime(300);
            userManager.save(anonUser);
        }
    }
    
    /**
     * Get the log factory.
     */
    public LogFactory getLogFactory() {
        return logFactory;
    }
    
    /**
     * Get user manager.
     */
    public UserManager getUserManager() {
        return (UserManager) userManagerBean.getBean();
    }
    
    /**
     * Get IP restrictor.
     */
    public IpRestrictor getIpRestrictor() {
        return (IpRestrictor) ipRestrictorBean.getBean();
    }
     
    /**
     * Get connection manager.
     */
    public ConnectionManager getConnectionManager() {
        return (ConnectionManager) connectionManagerBean.getBean();
    } 
    
    /**
     * Get file system manager.
     */
    public FileSystemManager getFileSystemManager() {
        return (FileSystemManager) fileSystemManagerBean.getBean();
    }
     
    /**
     * Get message resource.
     */
    public MessageResource getMessageResource() {
        return (MessageResource) messageResourceBean.getBean();
    }
    
    /**
     * Get ftp statistics.
     */
    public FtpStatistics getFtpStatistics() {
        return (FtpStatistics) statisticsBean.getBean();
    }
    
    /**
     * Get ftplet handler.
     */
    public Ftplet getFtpletContainer() {
        return (Ftplet) ftpletContainerBean.getBean();
    }
    
    /**
     * Get the command factory.
     */
    public CommandFactory getCommandFactory() {
        return (CommandFactory) commandFactoryBean.getBean();
    }
    
    /**
     * Get Ftplet.
     */
    public Ftplet getFtplet(String name) {
        return ((FtpletContainer) ftpletContainerBean.getBean()).getFtplet(name);
    }
    
    /**
     * Close all the components.
     */
    public void dispose() {
        
        Iterator listenerIter = listeners.values().iterator();
        while (listenerIter.hasNext()) {
            Bean listenerBean = (Bean) listenerIter.next();
            listenerBean.destroyBean();
        }
        
        if(connectionManagerBean != null && connectionManagerBean.getBean() != null) {
            connectionManagerBean.destroyBean();
        }
        
        if(ftpletContainerBean != null && ftpletContainerBean.getBean() != null) {
            ftpletContainerBean.destroyBean();
        }
        
        if(userManagerBean != null && userManagerBean.getBean() != null) {
            userManagerBean.destroyBean();
        }
        
        if(ipRestrictorBean != null && ipRestrictorBean.getBean() != null) {
            ipRestrictorBean.destroyBean();
        }
        
        if(fileSystemManagerBean != null && fileSystemManagerBean.getBean() != null) {
            fileSystemManagerBean.destroyBean();
        }
        
        if(statisticsBean != null && statisticsBean.getBean() != null) {
            statisticsBean.destroyBean();
        }
        
        if(messageResourceBean != null && messageResourceBean.getBean() != null) {
            messageResourceBean.destroyBean();
        }
        
        if(logFactory != null) {
            logFactory.release();
            logFactory = null;
        }
    }

    public Listener getListener(String name) {
        Bean listenerBean = (Bean) listeners.get(name);
        
        if(listenerBean != null) {
            return (Listener) listenerBean.getBean();
        } else {
            return null;
        }
    }

    public Listener[] getListeners() {
        Collection listenerBeans = listeners.values();
        Iterator listenerIter = listenerBeans.iterator();
        
        
        Listener[] listenerArray = new Listener[listenerBeans.size()];
        
        int counter = 0;
        while (listenerIter.hasNext()) {
            Bean bean = (Bean) listenerIter.next();
            
            listenerArray[counter] = (Listener) bean.getBean();
            
            counter++;
        }
        
        return listenerArray;
    }
} 
