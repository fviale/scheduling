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
package org.ow2.proactive.resourcemanager.frontend;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.objectweb.proactive.annotation.PublicAPI;
import org.objectweb.proactive.core.node.Node;
import org.objectweb.proactive.core.util.wrapper.BooleanWrapper;
import org.objectweb.proactive.core.util.wrapper.IntWrapper;
import org.objectweb.proactive.core.util.wrapper.StringWrapper;
import org.ow2.proactive.authentication.UserData;
import org.ow2.proactive.permissions.*;
import org.ow2.proactive.resourcemanager.common.RMState;
import org.ow2.proactive.resourcemanager.common.event.RMEvent;
import org.ow2.proactive.resourcemanager.common.event.RMNodeHistory;
import org.ow2.proactive.resourcemanager.common.event.RMNodeSourceEvent;
import org.ow2.proactive.resourcemanager.exception.RMException;
import org.ow2.proactive.resourcemanager.frontend.topology.Topology;
import org.ow2.proactive.resourcemanager.nodesource.common.NodeSourceConfiguration;
import org.ow2.proactive.resourcemanager.nodesource.common.PluginDescriptor;
import org.ow2.proactive.scripting.Script;
import org.ow2.proactive.scripting.ScriptResult;
import org.ow2.proactive.scripting.SelectionScript;
import org.ow2.proactive.topology.descriptor.TopologyDescriptor;
import org.ow2.proactive.utils.Criteria;
import org.ow2.proactive.utils.NodeSet;


/**
 * This class represents the interface of the resource manager.
 * <p>
 * The resource manager is used to aggregate resources across the network which are represented by ProActive nodes.
 * Its main features are
 * <ul>
 * <li> deployment, acquisition and release of ProActive nodes to/from an underlying infrastructure</li>
 * <li> providing nodes for computations, based on clients criteria (@see {@link SelectionScript})</li>
 * <li> maintaining and monitoring its list of resources and managing their states (free, busy, down...)</li>
 * </ul>
 * <p>
 * This interface provides means to create/remove node sources in the resource manager, add remove nodes to node sources,
 * track the state of the resource manager and get nodes for computations.
 * <p>
 * All the methods of this interface are asynchronous.
 */
@PublicAPI
public interface ResourceManager extends ServiceUsingPermission {

    /**
     * Defines a new node source in the resource manager.
     *
     * @param nodeSourceName the name of the node source
     * @param infrastructureType type of the underlying infrastructure
     * @param infraParams parameters for infrastructure creation
     * @param policyType name of the policy type. It passed as a string due to plug-able approach
     * @param policyParams parameters for policy creation
     * @param nodesRecoverable whether the nodes can be recovered in case of a scheduler crash
     * @return true if a new node source was created successfully, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper defineNodeSource(String nodeSourceName, String infrastructureType, Object[] infraParams,
            String policyType, Object[] policyParams, boolean nodesRecoverable);

    /**
     * Edit an existing node source in the resource manager.
     *
     * @param nodeSourceName the name of the node source to edit
     * @param infrastructureType type of the underlying infrastructure
     * @param infraParams parameters for infrastructure creation
     * @param policyType name of the policy type. It passed as a string due to plug-able approach
     * @param policyParams parameters for policy creation
     * @param nodesRecoverable whether the nodes can be recovered in case of a scheduler crash
     * @return true if the node source was edited successfully, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper editNodeSource(String nodeSourceName, String infrastructureType, Object[] infraParams,
            String policyType, Object[] policyParams, boolean nodesRecoverable);

    /**
     * Update the infrastructure or policy parameters of a node source that
     * marked with 'dynamic'. Only these parameters will be updated.
     *
     * @param nodeSourceName the name of the node source
     * @param infrastructureType type of the underlying infrastructure
     * @param infraParams parameters of the infrastructure, including the dynamic parameters values to update
     * @param policyType name of the policy type
     * @param policyParams parameters of the policy, including the dynamic parameters values to update
     * @return true if a new node source was edited successfully, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper updateDynamicParameters(String nodeSourceName, String infrastructureType, Object[] infraParams,
            String policyType, Object[] policyParams);

    /**
     * @deprecated  As of version 8.1, replaced by {@link #defineNodeSource(String, String, Object[], String, Object[],
     * boolean)} and {@link #deployNodeSource(String)}
     *
     * The node source is the set of nodes acquired from specific infrastructure and characterized
     * by particular acquisition policy.
     * <p>
     * This method creates a new node source with specified name, infrastructure manager and acquisition policy.
     * Parameters required to create infrastructure manager and policy can be obtained from
     * corresponding {@link PluginDescriptor}.
     *
     * @param nodeSourceName the name of the node source
     * @param infrastructureType type of the underlying infrastructure
     * @param infrastructureParameters parameters for infrastructure creation
     * @param policyType name of the policy type. It passed as a string due to plug-able approach
     * @param policyParameters parameters for policy creation
     * @param nodesRecoverable whether the nodes can be recovered in case of a scheduler crash
     * @return true if a new node source was created successfully, runtime exception otherwise
     */
    @Deprecated
    @RoleNSAdmin
    BooleanWrapper createNodeSource(String nodeSourceName, String infrastructureType, Object[] infrastructureParameters,
            String policyType, Object[] policyParameters, boolean nodesRecoverable);

    /**
     * Start acquiring the nodes of a node source that has been defined before.
     * If the node source is already deployed, then this method does nothing.
     *
     * @param nodeSourceName the name of the node source to deploy
     * @return true if the node source was deployed successfully, false if the
     * node source is already deployed, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper deployNodeSource(String nodeSourceName);

    /**
     * Remove the nodes of a node source that has been deployed before, and
     * keep the node source defined in the resource manager.
     * If the node source is already undeployed, then this method does nothing.
     *
     * @param nodeSourceName the name of the node source to undeploy
     * @param preempt if true remove the nodes immediately without waiting them to be freed.
     * @return true if the node source was undeployed successfully, false if the
     * node source is already undeployed, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper undeployNodeSource(String nodeSourceName, boolean preempt);

    /**
     * Remove the nodes of a node source that has been deployed before, and
     * keep the node source defined in the resource manager, then deploy the node source
     * If the node source is undeployed, then this method does nothing.
     *
     * @param nodeSourceName the name of the node source to redeploy
     * @return true if the node source was redeployed successfully, false if the
     * node source is undeployed, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper redeployNodeSource(String nodeSourceName);

    /**
     * Remove a node source from the RM.
     * All nodes handled by the node source are removed.
     *
     * @param sourceName name of the source to remove.
     * @param preempt if true remove the node immediately without waiting while it will be freed.
     * @return true if the node source was removed successfully, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper removeNodeSource(String sourceName, boolean preempt);

    /**
     * Returns the list of existing node source infrastructures 
     *
     * @return the list of existing node source infrastructures 
     */
    @RoleRead
    List<RMNodeSourceEvent> getExistingNodeSourcesList();

    /**
     * Returns the list of supported node source infrastructures descriptors.
     *
     * @return the list of supported node source infrastructures descriptors
     */
    @RoleRead
    Collection<PluginDescriptor> getSupportedNodeSourceInfrastructures();

    /**
     * Returns the list of supported node source policies descriptors.
     *
     * @return the list of supported node source policies descriptors
     */
    @RoleRead
    Collection<PluginDescriptor> getSupportedNodeSourcePolicies();

    /**
     * Returns the current configuration of a node source.
     */
    @RoleRead
    NodeSourceConfiguration getNodeSourceConfiguration(String nodeSourceName);

    /**
     * Each node source scan its nodes periodically to check their states.
     * This method changes the period of nodes scanning.
     *
     * @param frequency the frequency to set to the node source in ms.
     * @param sourceName name of the node source to set the frequency
     * @return true if ping frequency is successfully changed, runtime exception otherwise
     */
    @RoleNSAdmin
    BooleanWrapper setNodeSourcePingFrequency(int frequency, String sourceName);

    /**
     * Returns the ping frequency of a node source.
     *
     * @param sourceName name of the node source
     * @return the ping frequency
     */
    @RoleRead
    IntWrapper getNodeSourcePingFrequency(String sourceName);

    /**
     * Adds an existing node to the default node source of the resource manager.
     *
     * @param nodeUrl URL of the node to add.
     * @return true if new node is added successfully, runtime exception otherwise
     */
    @RoleProvider
    BooleanWrapper addNode(String nodeUrl);

    /**
     * Adds an existing node to the particular node source.
     *
     * @param nodeUrl URL of the node to add.
     * @param sourceName name of the static node source that will handle the node
     * @return true if new node is added successfully, runtime exception otherwise
     */
    @RoleProvider
    BooleanWrapper addNode(String nodeUrl, String sourceName);

    /**
     * Acquires new nodes in the particular node source.
     *
     * @param sourceName name of the node source that will acquire the new nodes
     * @param numberNodes the number of nodes to acquire
     * @param timeout the maxime waiting time (in milliseconds) before starting to acquire nodes
     * @param nodeConfiguration configuration of acquiring nodes
     * @return true if the request of acquiring nodes is successfully received, false or exception otherwise
     */
    @RoleProvider
    BooleanWrapper acquireNodes(String sourceName, int numberNodes, long timeout, Map<String, ?> nodeConfiguration);

    /**
     * Removes a node from the resource manager.
     *
     * @param nodeUrl URL of the node to remove.
     * @param preempt if true remove the node immediately without waiting while it will be freed.
     * @return true if the node is removed successfully, false or exception otherwise
     */
    @RoleProvider
    BooleanWrapper removeNode(String nodeUrl, boolean preempt);

    /**
     * Locks the set of nodes and makes them not available for others.
     * The node state "locked" means that node cannot be used for computations by anyone.
     *
     * Could be called only by node administrator, which is one of the following: rm admin,
     * node source admin or node provider.
     *
     * Nodes can be locked whatever their state is.
     *
     * @param urls is a set of nodes
     * @return {@code true} if all the nodes become locked, {@code false} otherwise.
     *
     */
    @RoleWrite
    BooleanWrapper lockNodes(Set<String> urls);

    /**
     * Unlock nodes. The specified nodes become available to other users for computations.
     * Real eligibility still depends on the Node state.
     *
     * Could be called only by node administrator, which is one of the following: rm admin,
     * node source admin or node provider.
     *
     * @param urls is a set of nodes to be unlocked.
     *
     * @return {@code true} if all the nodes are unlocked with success, {@code false} otherwise.
     */
    @RoleWrite
    BooleanWrapper unlockNodes(Set<String> urls);

    /**
     * Returns true if the node nodeUrl is registered (i.e. known by the RM) and not down.
     *
     * @param nodeUrl of node to ping.
     * @return true if the node nodeUrl is registered and not down.
     */
    @RoleRead
    BooleanWrapper nodeIsAvailable(String nodeUrl);

    /**
     * This method is called periodically by ProActive Nodes to inform the
     * Resource Manager of a possible reconnection. The method is also used by
     * ProActive Nodes to know if they are still known by the Resource Manager.
     * For instance a Node which has been removed by a user from the
     * Resource Manager is no longer known.
     *
     * @param nodeUrls the URLs of the workers associated to the node that publishes the update.
     *
     * @return The set of worker node URLs that are unknown to the Resource Manager
     * (i.e. have been removed by a user).
     */
    @RoleWrite
    Set<String> setNodesAvailable(Set<String> nodeUrls);

    /**
     * Returns true if the resource manager is operational and a client is connected.
     *
     * Throws SecurityException if client is not connected.
     * @return true if the resource manager is operational, false otherwise
     */
    @RoleBasic
    BooleanWrapper isActive();

    /**
     * Returns the resource manager summary state.
     * To retrieve detailed state use {@link RMMonitoring}.getState() method.
     *
     * @return the resource manager summary state.
     */
    @RoleRead
    RMState getState();

    /**
     * Returns the monitoring interface to manager listeners of the resource manager.
     *
     * @return the resource manager monitoring interface
     */
    @RoleRead
    RMMonitoring getMonitoring();

    /**
     * Returns the string content of the thread dump asked to the {@link Node}
     * of the Resource Manager.
     *
     * @return the thread dump on the RM node
     */
    @RoleAdmin
    StringWrapper getRMThreadDump();

    /**
     * Returns the string content of the thread dump asked to the {@link Node}
     * identified by the given URL.
     *
     * @param nodeUrl node to ask the thread dump to
     * @return the thread dump on this node
     */
    @RoleNSAdmin
    StringWrapper getNodeThreadDump(String nodeUrl);

    /**
     * Returns a list of all alive Nodes Urls. Alive means neither down nor currently deploying.
     * @return list of node urls
     */
    @RoleRead
    Set<String> listAliveNodeUrls();

    /**
     * Returns a list of all alive Nodes Urls associated with the given node sources.
     * @param nodeSourceNames set of node sources containing the nodes.
     * @return list of node urls
     */
    @RoleRead
    Set<String> listAliveNodeUrls(Set<String> nodeSourceNames);

    /**
     * Returns a list of all nodes Urls known by RMCore.
     * @return set of node urls
     */
    @RoleRead
    Set<String> listNodeUrls();

    /**
     * Finds "number" nodes for computations according to the selection script.
     * All nodes which are returned to the client as marked internally as busy and cannot
     * be used by others until the client frees them.
     * <p>
     * If the resource manager does not have enough nodes it returns as much as it
     * has, but only those which correspond to the selection criteria.
     *
     * @param number the number of nodes
     * @param selectionScript criterion to be verified by the returned nodes
     * @return a list of nodes
     */
    @Deprecated
    @RoleWrite
    NodeSet getAtMostNodes(int number, SelectionScript selectionScript);

    /**
     * Finds "number" nodes for computations according to the selection script.
     * All nodes which are returned to the client as marked internally as busy and cannot
     * be used by others until the client frees them.
     * <p>
     * If the resource manager does not have enough nodes it returns as much as it
     * has, but only those which correspond the to selection criteria.
     *
     * @param number the number of nodes
     * @param selectionScript criterion to be verified by the returned nodes
     * @param exclusion a list of node which should not be in the result set
     * @return a list of nodes
     */
    @Deprecated
    @RoleWrite
    NodeSet getAtMostNodes(int number, SelectionScript selectionScript, NodeSet exclusion);

    /**
     * Finds "number" nodes for computations according to the selection scripts
     * (node must be complaint to all scripts).
     * All nodes which are returned to the client as marked internally as busy and cannot
     * be used by others until the client frees them.
     * <p>
     * If the resource manager does not have enough nodes it returns as much as it
     * has, but only those which correspond the to selection criteria.
     *
     * @param number the number of nodes
     * @param selectionScriptsList criteria to be verified by the returned nodes
     * @param exclusion a list of node which should not be in the result set
     * @return a list of nodes
     */
    @Deprecated
    @RoleWrite
    NodeSet getAtMostNodes(int number, List<SelectionScript> selectionScriptsList, NodeSet exclusion);

    /**
     * Finds "number" nodes for computations according to the selection scripts
     * (node must be complaint to all scripts).
     * All nodes which are returned to the client as marked internally as busy and cannot
     * be used by others until the client frees them.
     * <p>
     * If the resource manager does not have enough nodes it returns as much as it
     * has, but only those which correspond the to selection criteria.
     *
     * @param number the number of nodes
     * @param descriptor the topology descriptor of nodes 
     * @see TopologyDescriptor
     * @param selectionScriptsList criteria to be verified by the returned nodes
     * @param exclusion a list of node which should not be in the result set
     * @return a list of nodes
     */
    @Deprecated
    @RoleWrite
    NodeSet getAtMostNodes(int number, TopologyDescriptor descriptor, List<SelectionScript> selectionScriptsList,
            NodeSet exclusion);

    /**
     * Finds "number" nodes for computations according to the selection scripts
     * (node must be complaint to all scripts).
     * All nodes which are returned to the client as marked internally as busy and cannot
     * be used by others until the client frees them.
     * <p>
     * If the resource manager does not have enough nodes the result depends on the bestEffort
     * mode. If set to true, the method returns as many node as it has, 
     * but only those which correspond the to selection criteria. If bestEffort set to false
     * the method returns either 0 or all required nodes.
     * 
     *
     * @param number the number of nodes
     * @param descriptor the topology descriptor of nodes 
     * @see TopologyDescriptor
     * @param selectionScriptsList criteria to be verified by the returned nodes
     * @param exclusion a list of node which should not be in the result set
     * @param bestEffort the mode of node aggregation
     *  
     * @return a list of nodes
     */
    @Deprecated
    @RoleWrite
    NodeSet getNodes(int number, TopologyDescriptor descriptor, List<SelectionScript> selectionScriptsList,
            NodeSet exclusion, boolean bestEffort);

    /**
     * Finds and books nodes for computations.
     * Nodes should satisfy specified criteria. 
     * 
     * @param criteria criteria to select nodes
     * @see Criteria
     * @return a list of nodes according to the criteria
     */
    @RoleWrite
    NodeSet getNodes(Criteria criteria);

    /**
     * Releases the node after computations. The specified node is marked as free and become
     * available to other users.
     *
     * @param node the node to be released
     * @return true if the node has been released successfully, runtime exception otherwise.
     * {@link SecurityException} may be thrown if the user does not have right to release the node or it tries to release
     * a foreign node.
     */
    @RoleWrite
    BooleanWrapper releaseNode(Node node);

    /**
     * Releases nodes after computations. The specified node is marked as free and become
     * available to other users.
     *
     * @param nodes the set of nodes to be released
     * @return true if nodes have been released successfully, runtime exception otherwise.
     * {@link SecurityException} may be thrown if the user does not have right to release one of nodes or it tries to release
     * a foreign node.
     */
    @RoleWrite
    BooleanWrapper releaseNodes(NodeSet nodes);

    /**
     * Disconnects from resource manager and releases all the nodes taken by user for computations.
     *
     * @return true if successfully disconnected, runtime exception otherwise
     */
    @RoleBasic
    BooleanWrapper disconnect();

    /**
     * Initiate the shutdowns the resource manager. During the shutdown resource manager
     * removed all the nodes and kills them if necessary.
     * <p>
     * {@link RMEvent}(SHUTDOWN) will be send when the shutdown is finished.
     *
     * @return true if the shutdown process is successfully triggered, runtime exception otherwise
     */
    @RoleAdmin
    BooleanWrapper shutdown(boolean preempt);

    /**
     * Returns the topology information of nodes.
     * @return nodes topology
     */
    @RoleRead
    Topology getTopology();

    /**
     * Checks if the currently connected user is the node administrator
     * @return true if yes, false otherwise
     */
    @RoleBasic
    BooleanWrapper isNodeAdmin(String nodeUrl);

    /**
     * Checks if the currently connected user can use node for computations
     * @return true if yes, false otherwise
     */
    @RoleBasic
    BooleanWrapper isNodeUser(String nodeUrl);

    /**
     * Executes the script on the specified targets depending on the target type.
     *
     * @param script a selection script to execute.
     * @param targetType must be either NODE_URL, NODESOURCE_NAME or HOSTNAME
     * @param targets are names of particular resources
     *
     * @return the {@link ScriptResult} corresponding to the script execution.
     */
    @RoleNSAdmin
    <T> List<ScriptResult<T>> executeScript(Script<T> script, String targetType, Set<String> targets);

    /**
     * Executes the script on the specified targets depending on the target type.
     *
     * @param script a selection script to execute.
     * @param scriptEngine script engine name
     * @param targetType must be either NODE_URL, NODESOURCE_NAME or HOSTNAME
     * @param targets are names of particular resources
     * @return the {@link ScriptResult} corresponding to the script execution.
     */
    @RoleNSAdmin
    List<ScriptResult<Object>> executeScript(String script, String scriptEngine, String targetType,
            Set<String> targets);

    /**
     * Returns the user currently connected
     * @return user name
     */
    @RoleBasic
    StringWrapper getCurrentUser();

    /**
     * Returns the current user Subject
     * @return subject
     */
    @RoleBasic
    Subject getCurrentUserSubject();

    /**
     * Returns the groups associated with the current connected user.
     * @return a set of groups
     */
    @RoleBasic
    UserData getCurrentUserData();

    /**
     * Release the nodes that are busy and that are not part of the given node set
     * @param verifiedBusyNodes nodes that should not be released
     */
    @RoleWrite
    void releaseBusyNodesNotInList(List<NodeSet> verifiedBusyNodes);

    /**
     * Check whether the nodes in the given node set are known by the resource
     * manager
     *
     * @param nodes the node set to verify
     * @return whether all nodes in the node set appear in the resource manager
     */
    @RoleRead
    boolean areNodesKnown(NodeSet nodes);

    /**
     * Check whether the nodes in the given node set can be recovered in case
     * of a crash
     *
     * @param nodes the node set to verify
     * @return a map containing recoverable status for each node of the node set
     */
    @RoleRead
    Map<String, Boolean> areNodesRecoverable(NodeSet nodes);

    /**
     * Set the amount of nodes currently needed by the resource manager
     * @param neededNodes number of nodes needed (by tenant when configured)
     */
    @RoleWrite
    void setNeededNodes(Map<String, Integer> neededNodes);

    /**
     * Return the associations of infrastructures and policy
     * For each infrastructure name, the list of policies which can be associated
     * @return infrastructure and policies associations
     */
    @RoleRead
    Map<String, List<String>> getInfrasToPoliciesMapping();

    /**
     * Return all node state events for the given period
     * @param windowStart period start time
     * @param windowEnd period end time
     * @return a list of node state events
     */
    @RoleRead
    List<RMNodeHistory> getNodesHistory(long windowStart, long windowEnd);

    /**
     * Add a token to the given node
     * @param nodeUrl url of the node
     * @param token token to add
     * @throws RMException
     */
    @RoleWrite
    void addNodeToken(String nodeUrl, String token) throws RMException;

    /**
     * Remove a token from the given node
     * @param nodeUrl url of the node
     * @param token token to remove
     * @throws RMException
     */
    @RoleWrite
    void removeNodeToken(String nodeUrl, String token) throws RMException;

    /**
     * Overwrite the tokens list associated with the given node
     * @param nodeUrl url of the node
     * @param tokens list of tokens
     * @throws RMException
     */
    @RoleWrite
    void setNodeTokens(String nodeUrl, List<String> tokens) throws RMException;

    /**
     * Get the list of tokens associated with the given node
     * @param nodeUrl url of the node
     * @return list of tokens
     * @throws RMException
     */
    @RoleRead
    List<String> getNodeTokens(String nodeUrl) throws RMException;

    /**
     * Get the list of tokens associated with all resource manager nodes
     * @return nodes / token list association
     * @throws RMException
     */
    @RoleRead
    Map<String, List<String>> getAllNodesTokens() throws RMException;

    /**
     * Get the list of tokens associated with all resource manager eligible nodes
     * @return eligible nodes / token list association
     * @throws RMException
     */
    @RoleRead
    Map<String, List<String>> getAllEligibleNodesTokens() throws RMException;

    /**
     * Return the set of tags associated with the given node
     * @param nodeUrl url of the node
     * @return set of tags
     * @throws RMException
     */
    @RoleRead
    Set<String> getNodeTags(String nodeUrl) throws RMException;

    /**
     * Returns a list of nodes Urls which contain the specified tag
     * @param tag the expected tag which should be contained in the returned node
     * @return node urls which contain the tag
     */
    @RoleRead
    Set<String> getNodesByTag(String tag);

    /**
     * Returns a list of nodes Urls which contain all or any specified list of tags
     * @param tags list of tags
     * @param all When true, the search return nodes which contain all tags;
     *            when false, the search return nodes which contain any tag among the list tags.
     * @return nodes urls which contain all or any specified list of tags
     */
    @RoleRead
    Set<String> getNodesByTags(Set<String> tags, boolean all);

    /**
     * Check is the user has admin permission for the given node or it is a node source provider
     * @param nodeUrl the node url
     * @param provider if false, the method will check if the user is admin, if true, the method will also check if the node is a provider
     * @return true if the user has permission to access the node, false otherwise
     */
    @RoleBasic
    BooleanWrapper checkNodePermission(String nodeUrl, boolean provider);

    /**
     * Check is the user has admin permission for the given node source or it is a node source provider
     * @param nodeSourceName the node source name
     * @param provider if false, the method will check if the user is admin, if true, the method will also check if the node is a provider
     * @return true if the user has permission to access the nodeSource, false otherwise
     */
    @RoleBasic
    BooleanWrapper checkNodeSourcePermission(String nodeSourceName, boolean provider);

}
