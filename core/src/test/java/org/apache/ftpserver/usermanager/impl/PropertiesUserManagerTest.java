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

package org.apache.ftpserver.usermanager.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.UserManagerFactory;
import org.apache.ftpserver.usermanager.impl.ClearTextPasswordEncryptor;
import org.apache.ftpserver.util.IoUtils;

/**
*
* @author The Apache MINA Project (dev@mina.apache.org)
* @version $Rev$, $Date$
*
*/
public class PropertiesUserManagerTest extends UserManagerTestTemplate {

    private static final File TEST_DIR = new File("test-tmp");

    private static final File USERS_FILE = new File(TEST_DIR, "users.props");

    private void createUserFile() throws IOException {
        Properties users = new Properties();
        users.setProperty("ftpserver.user.user1.userpassword", "pw1");
        users.setProperty("ftpserver.user.user1.homedirectory", "home");

        users.setProperty("ftpserver.user.user2.userpassword", "pw2");
        users.setProperty("ftpserver.user.user2.homedirectory", "home");
        users.setProperty("ftpserver.user.user2.writepermission", "true");
        users.setProperty("ftpserver.user.user2.enableflag", "false");
        users.setProperty("ftpserver.user.user2.idletime", "2");
        users.setProperty("ftpserver.user.user2.uploadrate", "5");
        users.setProperty("ftpserver.user.user2.downloadrate", "1");
        users.setProperty("ftpserver.user.user2.maxloginnumber", "3");
        users.setProperty("ftpserver.user.user2.maxloginperip", "4");

        users.setProperty("ftpserver.user.user3.userpassword", "");
        users.setProperty("ftpserver.user.user3.homedirectory", "home");

        FileOutputStream fos = new FileOutputStream(USERS_FILE);
        users.store(fos, null);

        fos.close();
    }

    protected UserManagerFactory createUserManagerFactory() throws FtpException {
        PropertiesUserManagerFactory um = new PropertiesUserManagerFactory();
        um.setFile(USERS_FILE);
        um.setPasswordEncryptor(new ClearTextPasswordEncryptor());

        return um;
    }

    protected void setUp() throws Exception {

        TEST_DIR.mkdirs();

        createUserFile();

        super.setUp();
    }

    protected void tearDown() throws Exception {
        super.tearDown();

        IoUtils.delete(TEST_DIR);
    }

}