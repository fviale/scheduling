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
package org.ow2.proactive.scheduler.common.job;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;

import org.objectweb.proactive.annotation.PublicAPI;


/**
 * SchedulerUser is an internal representation of a user.<br>
 * It provides some information like user name, admin status, etc...
 *
 * @author The ProActive Team
 * 
 * $Id$
 */
@PublicAPI
public abstract class UserIdentification implements Serializable, Comparable<UserIdentification> {

    /** Value for  */
    public static final int SORT_BY_NAME = 1;

    /**  */
    public static final int SORT_BY_SUBMIT = 3;

    /**  */
    public static final int SORT_BY_HOST = 4;

    /**  */
    public static final int SORT_BY_CONNECTION = 5;

    /**  */
    public static final int SORT_BY_LASTSUBMIT = 6;

    /**  */
    public static final int ASC_ORDER = 1;

    /**  */
    public static final int DESC_ORDER = 2;

    private static int currentSort = SORT_BY_NAME;

    private static int currentOrder = ASC_ORDER;

    protected volatile boolean toRemove = false;

    /**
     * To get the user name
     *
     * @return the user name
     */
    public abstract String getUsername();

    /**
     * To get the groups associated with this user name
     *
     * @return a set of groups
     */
    public abstract Set<String> getGroups();

    /**
     * check if the user has access to all tenants
     */
    public abstract boolean isAllTenantPermission();

    /**
     * check if the user has the rights to read his groups roles
     */
    public abstract boolean isRoleReadPermission();

    /**
     * check if the user has the rights to modify existing groups/roles (super admin)
     */
    public abstract boolean isRoleAdminPermission();

    /**
     * Check if the user has all job planner permission
     */
    public abstract boolean isAllJobPlannerPermission();

    /**
     * Check if the user has all catalog permission
     */
    public abstract boolean isAllCatalogPermission();

    /**
     * Check if the user can create a job-planner association
     */
    public abstract boolean isCanCreateAssociationPermission();

    /**
     * Check if the user has all Service Automation permissions
     */
    public abstract boolean isPcaAdminPermission();

    /**
     * Check if the user has all Notification Service permissions;
     */
    public abstract boolean isNotificationAdminPermission();

    /**
     * Check if the user has all Resource Manager permissions
     */
    public abstract boolean isRMCoreAllPermission();

    /**
     * Check if the user has all Scheduler permissions
     * @return
     */
    public abstract boolean isSchedulerAdminPermission();

    /**
     * Check if the user has permission to handle only its jobs
     */
    public abstract boolean isHandleOnlyMyJobsPermission();

    /**
     * Check if the user has permission to read other users' jobs
     */
    public abstract boolean isOtherUsersJobReadPermission();

    /**
     * Check if the user has permission to manage users
     */
    public abstract boolean isManageUsersPermission();

    /**
     * Check if the user has permission to change a user's password
     */
    public abstract boolean isChangePasswordPermission();

    /**
     * Return the tenant associated with the current user, or null if no tenant is associated
     *
     * @return user tenant
     */
    public abstract String getTenant();

    /**
     * Return the domain name associated with the current user, or null if no domain is associated
     *
     * @return user domain
     */
    public abstract String getDomain();

    /**
     * Get the number of submit for this user.
     * 
     * @return the number of submit for this user.
     */
    public abstract int getSubmitNumber();

    /**
     * Get the host name of this user.
     * 
     * @return the host name of this user.
     */
    public abstract String getHostName();

    /**
     * Get the time of the connection of this user.
     * 
     * @return the time of the connection of this user.
     */
    public abstract long getConnectionTime();

    /**
     * Get the last time this user has submit a job.
     * 
     * @return the last time this user has submit a job.
     */
    public abstract long getLastSubmitTime();

    /**
     * Get the myEventsOnly.
     *
     * @return the myEventsOnly.
     */
    public abstract boolean isMyEventsOnly();

    /**
     * Get the session for this user (timer task)
     *
     * @return the session for this user, null if it has no session.
     */
    public abstract TimerTask getSession();

    /**
     * Set the field to sort on.
     *
     * @param sortBy
     *            the field on which the sort will be made.
     */
    public static void setSortingBy(int sortBy) {
        currentSort = sortBy;
    }

    /**
     * Set the order for the next sort.
     *
     * @param order the new order to set.
     */
    public static void setSortingOrder(int order) {
        if ((order == ASC_ORDER) || (order == DESC_ORDER)) {
            currentOrder = order;
        } else {
            currentOrder = ASC_ORDER;
        }
    }

    /**
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     * @param user The user to compare to <i>this</i> user.
     * @return  a negative integer, zero, or a positive integer as this object
     *		is less than, equal to, or greater than the specified object.
     */
    public int compareTo(UserIdentification user) {
        switch (currentSort) {
            case SORT_BY_SUBMIT:
                return (currentOrder == ASC_ORDER) ? getSubmitNumber() - user.getSubmitNumber()
                                                   : user.getSubmitNumber() - getSubmitNumber();
            case SORT_BY_HOST:
                return (currentOrder == ASC_ORDER) ? getHostName().compareTo(user.getHostName())
                                                   : user.getHostName().compareTo(getHostName());
            case SORT_BY_CONNECTION:
                return (currentOrder == ASC_ORDER) ? (int) (getConnectionTime() - user.getConnectionTime())
                                                   : (int) (user.getConnectionTime() - getConnectionTime());
            case SORT_BY_LASTSUBMIT:
                return (currentOrder == ASC_ORDER) ? (int) (getLastSubmitTime() - user.getLastSubmitTime())
                                                   : (int) (user.getLastSubmitTime() - getLastSubmitTime());
            default:
                return (currentOrder == ASC_ORDER) ? getHostName().compareTo(user.getHostName())
                                                   : user.getHostName().compareTo(getHostName());
        }
    }

    /**
     * Returns true if this user has to be removed, false if not.
     * 
     * @return true if this user has to be removed, false if not.
     */
    public boolean isToRemove() {
        return toRemove;
    }

}
