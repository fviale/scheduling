/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.scheduler.authentication;

import org.apache.log4j.Logger;
import org.ow2.proactive.authentication.MultiLDAPLoginModule;


/**
 * LDAP login module implementation for scheduling. Extracts LDAP configuration files from
 * scheduler configuration and uses it to authenticate users.
 */
public class SchedulerMultiLDAPLoginModule extends MultiLDAPLoginModule {

    /**
     * Returns logger for authentication
     *
     * @return logger for authentication
     */
    public Logger getLogger() {
        return Logger.getLogger(SchedulerMultiLDAPLoginModule.class);
    }
}
