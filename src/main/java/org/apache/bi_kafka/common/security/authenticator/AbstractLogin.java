/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.bi_kafka.common.security.authenticator;

import org.apache.bi_kafka.common.security.auth.Login;
import org.bi_slf4j.Logger;
import org.bi_slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.*;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.sasl.RealmCallback;
import java.util.Map;

/**
 * Base login class that implements methods common to typical SASL mechanisms.
 */
public abstract class AbstractLogin implements Login {
    private static final Logger log = LoggerFactory.getLogger(AbstractLogin.class);

    private Configuration jaasConfig;
    private String loginContextName;
    private LoginContext loginContext;


    public void configure(Map<String, ?> configs, Configuration jaasConfig, String loginContextName) {
        this.jaasConfig = jaasConfig;
        this.loginContextName = loginContextName;
    }

    public LoginContext login() throws LoginException {
        loginContext = new LoginContext(loginContextName, null, new LoginCallbackHandler(), jaasConfig);
        loginContext.login();
        log.info("Successfully logged in.");
        return loginContext;
    }

    public Subject subject() {
        return loginContext.getSubject();
    }

    protected Configuration jaasConfig() {
        return jaasConfig;
    }

    /**
     * Callback handler for creating login context. Login callback handlers
     * should support the callbacks required for the login modules used by
     * the KafkaServer and KafkaClient contexts. Kafka does not support
     * callback handlers which require additional user input.
     *
     */
    public static class LoginCallbackHandler implements CallbackHandler {

        public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callback;
                    nc.setName(nc.getDefaultName());
                } else if (callback instanceof PasswordCallback) {
                    String errorMessage = "Could not login: the client is being asked for a password, but the Kafka" +
                                 " client code does not currently support obtaining a password from the user.";
                    throw new UnsupportedCallbackException(callback, errorMessage);
                } else if (callback instanceof RealmCallback) {
                    RealmCallback rc = (RealmCallback) callback;
                    rc.setText(rc.getDefaultText());
                } else {
                    throw new UnsupportedCallbackException(callback, "Unrecognized SASL Login callback");
                }
            }
        }
    }
}

