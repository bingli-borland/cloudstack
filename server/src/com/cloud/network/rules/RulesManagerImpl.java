/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.network.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.api.to.PortForwardingRuleTO;
import com.cloud.api.commands.ListPortForwardingRulesCmd;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IPAddressVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestIpType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkManager;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRule.State;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserContext;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

@Local(value={RulesManager.class, RulesService.class})
public class RulesManagerImpl implements RulesManager, RulesService, Manager {
    private static final Logger s_logger = Logger.getLogger(RulesManagerImpl.class);
    String _name;
    
    @Inject PortForwardingRulesDao _forwardingDao;
    @Inject FirewallRulesDao _firewallDao;
    @Inject IPAddressDao _ipAddressDao;
    @Inject UserVmDao _vmDao;
    @Inject AccountManager _accountMgr;
    @Inject NetworkManager _networkMgr;
    @Inject EventDao _eventDao;
    @Inject UsageEventDao _usageEventDao;
    @Inject DomainDao _domainDao;

    @Override
    public void detectRulesConflict(FirewallRule newRule, IpAddress ipAddress) throws NetworkRuleConflictException {
        assert newRule.getSourceIpAddressId() == ipAddress.getId() : "You passed in an ip address that doesn't match the address in the new rule";
        
        List<FirewallRuleVO> rules = _firewallDao.listByIpAndNotRevoked(newRule.getSourceIpAddressId(), null);
        assert (rules.size() >= 1) : "For network rules, we now always first persist the rule and then check for network conflicts so we should at least have one rule at this point.";
        
        for (FirewallRuleVO rule : rules) {
            if (rule.getId() == newRule.getId()) {
                continue;  // Skips my own rule.
            }
            
            if (rule.isOneToOneNat() && !newRule.isOneToOneNat()) {
                throw new NetworkRuleConflictException("There is 1 to 1 Nat rule specified for the ip address id=" + newRule.getSourceIpAddressId());
            } else if (!rule.isOneToOneNat() && newRule.isOneToOneNat()) {
                throw new NetworkRuleConflictException("There is already firewall rule specified for the ip address id=" + newRule.getSourceIpAddressId());
            }
            
            if (rule.getNetworkId() != newRule.getNetworkId() && rule.getState() != State.Revoke) {
                throw new NetworkRuleConflictException("New rule is for a different network than what's specified in rule " + rule.getXid());
            }
           
            if ((rule.getSourcePortStart() <= newRule.getSourcePortStart() && rule.getSourcePortEnd() >= newRule.getSourcePortStart()) || 
                (rule.getSourcePortStart() <= newRule.getSourcePortEnd() && rule.getSourcePortEnd() >= newRule.getSourcePortEnd()) ||
                (newRule.getSourcePortStart() <= rule.getSourcePortStart() && newRule.getSourcePortEnd() >= rule.getSourcePortStart()) ||
                (newRule.getSourcePortStart() <= rule.getSourcePortEnd() && newRule.getSourcePortEnd() >= rule.getSourcePortEnd())) {
                
                //we allow port forwarding rules with the same parameters but different protocols
                if (!(rule.getPurpose() == Purpose.PortForwarding && newRule.getPurpose() == Purpose.PortForwarding && !newRule.getProtocol().equalsIgnoreCase(rule.getProtocol()))) {
                    throw new NetworkRuleConflictException("The range specified, " + newRule.getSourcePortStart() + "-" + newRule.getSourcePortEnd() + ", conflicts with rule " + rule.getId() + " which has " + rule.getSourcePortStart() + "-" + rule.getSourcePortEnd());
                }
            }
        }
        
        if (s_logger.isDebugEnabled()) { 
            s_logger.debug("No network rule conflicts detected for " + newRule + " against " + (rules.size() - 1) + " existing rules");
        }
        
    }

    @Override
    public void checkIpAndUserVm(IpAddress ipAddress, UserVm userVm, Account caller) throws InvalidParameterValueException, PermissionDeniedException {
        if (ipAddress == null || ipAddress.getAllocatedTime() == null || ipAddress.getAllocatedToAccountId() == null) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid IP address specified.");
        }
        
        if (userVm == null) {
            return;
        }
        
        if (userVm.getState() == VirtualMachine.State.Destroyed || userVm.getState() == VirtualMachine.State.Expunging) {
            throw new InvalidParameterValueException("Invalid user vm: " + userVm.getId());
        }
        
        _accountMgr.checkAccess(caller, ipAddress);
        _accountMgr.checkAccess(caller, userVm);
        
        // validate that IP address and userVM belong to the same account
        if (ipAddress.getAllocatedToAccountId().longValue() != userVm.getAccountId()) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " owner is not the same as owner of virtual machine " + userVm.toString()); 
        }

        // validate that userVM is in the same availability zone as the IP address
        if (ipAddress.getDataCenterId() != userVm.getDataCenterId()) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule, IP address " + ipAddress + " is not in the same availability zone as virtual machine " + userVm.toString());
        }
        
    }
    
    @Override
    public void checkRuleAndUserVm(FirewallRule rule, UserVm userVm, Account caller) throws InvalidParameterValueException, PermissionDeniedException {
        if (userVm == null || rule == null) {
            return;
        }
        
        _accountMgr.checkAccess(caller, rule);
        _accountMgr.checkAccess(caller, userVm);
       
        if (userVm.getState() == VirtualMachine.State.Destroyed || userVm.getState() == VirtualMachine.State.Expunging) {
            throw new InvalidParameterValueException("Invalid user vm: " + userVm.getId());
        }
        
        if (rule.getAccountId() != userVm.getAccountId()) {
            throw new InvalidParameterValueException("Rule id=" + rule.getId() + " and vm id=" + userVm.getId() + " belong to different accounts");
        }
    }


    @Override @DB @ActionEvent (eventType=EventTypes.EVENT_NET_RULE_ADD, eventDescription="creating forwarding rule", create=true)
    public PortForwardingRule createPortForwardingRule(PortForwardingRule rule, Long vmId, boolean isNat) throws NetworkRuleConflictException {
        UserContext ctx = UserContext.current();
        Account caller = ctx.getCaller();
        
        Long ipAddrId = rule.getSourceIpAddressId();
        
        IPAddressVO ipAddress = _ipAddressDao.findById(ipAddrId);
        
        //Verify ip address existst and if 1-1 nat is enabled for it
        if (ipAddress == null) {
            throw new InvalidParameterValueException("Unable to create ip forwarding rule; ip id=" + ipAddrId + " doesn't exist in the system");
        } else {
            _accountMgr.checkAccess(caller, ipAddress);
        }
        
        Ip dstIp = rule.getDestinationIpAddress();
        long networkId;
        UserVmVO vm = null;
        Network network = null;
        if (vmId != null) {
            // validate user VM exists
            vm = _vmDao.findById(vmId);
            if (vm == null) {
                throw new InvalidParameterValueException("Unable to create ip forwarding rule on address " + ipAddress + ", invalid virtual machine id specified (" + vmId + ").");
            } else {
                checkRuleAndUserVm(rule, vm, caller);
            }
            dstIp = null;
            List<? extends Nic> nics = _networkMgr.getNics(vm);
            for (Nic nic : nics) {
                Network ntwk = _networkMgr.getNetwork(nic.getNetworkId());
                if (ntwk.getGuestType() == GuestIpType.Virtual && nic.getIp4Address() != null) {
                    network = ntwk;
                    dstIp = new Ip(nic.getIp4Address());
                    break;
                }
            }
            
            if (network == null) {
                throw new CloudRuntimeException("Unable to find ip address to map to in vm id=" + vmId);
            }
        } else {
            network = _networkMgr.getNetwork(rule.getNetworkId());
            if (network == null) {
                throw new InvalidParameterValueException("Unable to get the network " + rule.getNetworkId());
            }
        }

        _accountMgr.checkAccess(caller, network);
        
        networkId = network.getId();
        long accountId = network.getAccountId();
        long domainId = network.getDomainId();
        
        if (isNat && (ipAddress.isSourceNat() || !ipAddress.isOneToOneNat() || ipAddress.getAssociatedWithVmId() == null)) {
            throw new NetworkRuleConflictException("Can't do one to one NAT on ip address: " + ipAddress.getAddress());
        }
        
    
        //Verify that the network guru supports the protocol specified
        Map<Network.Capability, String> firewallCapability = _networkMgr.getServiceCapability(network.getDataCenterId(), Service.Firewall);
        String supportedProtocols = firewallCapability.get(Capability.SupportedProtocols).toLowerCase();
        if (!supportedProtocols.contains(rule.getProtocol().toLowerCase())) {
            throw new InvalidParameterValueException("Protocol " + rule.getProtocol() + " is not supported in zone " + network.getDataCenterId());
        }
        
        PortForwardingRuleVO newRule = 
            new PortForwardingRuleVO(rule.getXid(), 
                    rule.getSourceIpAddressId(), 
                    rule.getSourcePortStart(), 
                    rule.getSourcePortEnd(),
                    dstIp,
                    rule.getDestinationPortStart(), 
                    rule.getDestinationPortEnd(), 
                    rule.getProtocol().toLowerCase(), 
                    networkId,
                    accountId,
                    domainId, vmId, isNat);
        newRule = _forwardingDao.persist(newRule);

        try {
            detectRulesConflict(newRule, ipAddress);
            if (!_firewallDao.setStateToAdd(newRule)) {
                throw new CloudRuntimeException("Unable to update the state to add for " + newRule);
            }
            UserContext.current().setEventDetails("Rule Id: "+newRule.getId());
            UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_RULE_ADD, newRule.getAccountId(), 0, newRule.getId(), null);
            _usageEventDao.persist(usageEvent);
            return newRule;
        } catch (Exception e) {
            _forwardingDao.remove(newRule.getId());
            if (e instanceof NetworkRuleConflictException) {
                throw (NetworkRuleConflictException)e;
            }
            throw new CloudRuntimeException("Unable to add rule for the ip id=" + newRule.getSourceIpAddressId(), e);
        }
    }
    
    @Override
    public boolean enableOneToOneNat(long ipId, long vmId) throws NetworkRuleConflictException{
        IPAddressVO ipAddress = _ipAddressDao.findById(ipId);
        Account caller = UserContext.current().getCaller();
        
        UserVmVO vm = null;
        vm = _vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Can't enable one-to-one nat for the address " + ipAddress + ", invalid virtual machine id specified (" + vmId + ").");
        }
        
        checkIpAndUserVm(ipAddress, vm, caller);
        
        if (ipAddress.isSourceNat()) {
            throw new InvalidParameterValueException("Can't enable one to one nat, ip address id=" + ipId + " is a sourceNat ip address");
        }
       
        if (!ipAddress.isOneToOneNat()) {
            List<FirewallRuleVO> rules = _firewallDao.listByIpAndNotRevoked(ipId, false);
            if (rules != null && !rules.isEmpty()) {
                throw new NetworkRuleConflictException("Failed to enable one to one nat for the ip address id=" + ipAddress.getId() + " as it already has firewall rules assigned");
            }
        } else {
            if (ipAddress.getAssociatedWithVmId() != null && ipAddress.getAssociatedWithVmId().longValue() != vmId) {
                throw new NetworkRuleConflictException("Failed to enable one to one nat for the ip address id=" + ipAddress.getId() + " and vm id=" + vmId + " as it's already assigned to antoher vm");
            }
        } 
        
        ipAddress.setOneToOneNat(true);
        ipAddress.setAssociatedWithVmId(vmId);
        return _ipAddressDao.update(ipAddress.getId(), ipAddress);
       
    }
    
    protected Pair<Network, Ip> getUserVmGuestIpAddress(UserVm vm) {
        Ip dstIp = null;
        List<? extends Nic> nics = _networkMgr.getNics(vm);
        for (Nic nic : nics) {
            Network ntwk = _networkMgr.getNetwork(nic.getNetworkId());
            if (ntwk.getGuestType() == GuestIpType.Virtual) {
                dstIp = new Ip(nic.getIp4Address());
                return new Pair<Network, Ip>(ntwk, dstIp);
            }
        }
        
        throw new CloudRuntimeException("Unable to find ip address to map to in " + vm.getId());
    }
    
    @DB
    protected void revokeRule(FirewallRuleVO rule, Account caller, long userId) {
        if (caller != null) {
            _accountMgr.checkAccess(caller, rule);
        }
       
        Transaction txn = Transaction.currentTxn();
        txn.start();
        if (rule.getState() == State.Staged) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Found a rule that is still in stage state so just removing it: " + rule);
            }
            _firewallDao.remove(rule.getId());
        } else if (rule.getState() == State.Add || rule.getState() == State.Active) {
            rule.setState(State.Revoke);
            _firewallDao.update(rule.getId(), rule);
        }
        
        // Save and create the event
        String ruleName = rule.getPurpose() == Purpose.Firewall ? "Firewall" : (rule.isOneToOneNat() ? "ip forwarding" : "port forwarding");
        StringBuilder description = new StringBuilder("deleted ").append(ruleName).append(" rule [ipAddressId=").append(rule.getSourceIpAddressId()).append(":").append(rule.getSourcePortStart()).append("-").append(rule.getSourcePortEnd()).append("]");
        if (rule.getPurpose() == Purpose.PortForwarding) {
            PortForwardingRuleVO pfRule = (PortForwardingRuleVO)rule;
            description.append("->[").append(pfRule.getDestinationIpAddress()).append(":").append(pfRule.getDestinationPortStart()).append("-").append(pfRule.getDestinationPortEnd()).append("]");
        }
        description.append(" ").append(rule.getProtocol());

        txn.commit();
    }
    
    @Override @ActionEvent (eventType=EventTypes.EVENT_NET_RULE_DELETE, eventDescription="revoking forwarding rule", async=true)
    public boolean revokePortForwardingRule(long ruleId, boolean apply) {
        UserContext ctx = UserContext.current();
        Account caller = ctx.getCaller();
        
        PortForwardingRuleVO rule = _forwardingDao.findById(ruleId);
        if (rule == null) {
            throw new InvalidParameterValueException("Unable to find " + ruleId);
        }
        
        long ownerId = rule.getAccountId();
        
        _accountMgr.checkAccess(caller, rule);
        revokeRule(rule, caller, ctx.getCallerUserId());
        
        boolean success = false;
        
        if (apply) {
            success = applyPortForwardingRules(rule.getSourceIpAddressId(), true, caller);
        } else {
            success = true;
        }
        if(success){
            UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_RULE_DELETE, ownerId, 0, ruleId, null);
            _usageEventDao.persist(usageEvent);
        }
        return success;
    }
    
    @Override
    public boolean revokePortForwardingRule(long vmId) {
    	UserVmVO vm = _vmDao.findByIdIncludingRemoved(vmId);
    	if (vm == null) {
    		return false;
    	}
    	
    	List<PortForwardingRuleVO> rules = _forwardingDao.listByVm(vmId);
    	
    	if (rules == null || rules.isEmpty()) {
            return true;
        }
    	
    	for (PortForwardingRuleVO rule : rules) {
    		revokePortForwardingRule(rule.getId(), true);
    	}
        return true;
    }
    
    public List<? extends FirewallRule> listFirewallRules(long ipId) {
        return _firewallDao.listByIpAndNotRevoked(ipId, null);
    }

    @Override
    public List<? extends PortForwardingRule> listPortForwardingRulesForApplication(long ipId) {
        return _forwardingDao.listForApplication(ipId);
    }
    
    @Override
    public List<? extends PortForwardingRule> listPortForwardingRules(ListPortForwardingRulesCmd cmd) {
       Account caller = UserContext.current().getCaller();
       Long ipId = cmd.getIpAddressId();
       String path = null;
        
       Pair<String, Long> accountDomainPair = _accountMgr.finalizeAccountDomainForList(caller, cmd.getAccountName(), cmd.getDomainId());
       String accountName = accountDomainPair.first();
       Long domainId = accountDomainPair.second();
        
        if(ipId != null){
            IPAddressVO ipAddressVO = _ipAddressDao.findById(ipId);
            if (ipAddressVO == null || !ipAddressVO.readyToUse()) {
                throw new InvalidParameterValueException("Ip address id=" + ipId + " not ready for port forwarding rules yet");
            }
            _accountMgr.checkAccess(caller, ipAddressVO);
        }
        
        if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
            Domain domain = _accountMgr.getDomain(caller.getDomainId());
            path = domain.getPath();
        }
        
        Filter filter = new Filter(PortForwardingRuleVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal()); 
        SearchBuilder<PortForwardingRuleVO> sb = _forwardingDao.createSearchBuilder();
        sb.and("ip", sb.entity().getSourceIpAddressId(), Op.EQ);
        sb.and("accountId", sb.entity().getAccountId(), Op.EQ);
        sb.and("domainId", sb.entity().getDomainId(), Op.EQ);
        sb.and("oneToOneNat", sb.entity().isOneToOneNat(), Op.EQ);
        
        if (path != null) {
            //for domain admin we should show only subdomains information
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }
        
        SearchCriteria<PortForwardingRuleVO> sc = sb.create();
        
        if (ipId != null) {
            sc.setParameters("ip", ipId);
        }
        
        if (domainId != null) {
            sc.setParameters("domainId", domainId);
            if (accountName != null) {
                Account account = _accountMgr.getActiveAccount(accountName, domainId);
                sc.setParameters("accountId", account.getId());
            }
        }
        
        sc.setParameters("oneToOneNat", false);
        
        if (path != null) {
            sc.setJoinParameters("domainSearch", "path", path + "%");
        }
       
        return _forwardingDao.search(sc, filter);
    }

   
    @Override
    public boolean applyPortForwardingRules(long ipId, boolean continueOnError, Account caller){
        List<PortForwardingRuleVO> rules = _forwardingDao.listForApplication(ipId);
        if (rules.size() == 0) {
            s_logger.debug("There are no firwall rules to apply for ip id=" + ipId);
            return true;
        }

        if (caller != null) {
            _accountMgr.checkAccess(caller, rules.toArray(new PortForwardingRuleVO[rules.size()]));
        }
        
        try {
            if (!applyRules(rules, continueOnError)) {
                return false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to apply firewall rules due to ", ex);
            return false;
        }
        
        return true;
    }
    
    @Override
    public boolean applyPortForwardingRulesForNetwork(long networkId, boolean continueOnError, Account caller){
        List<PortForwardingRuleVO> rules = listByNetworkId(networkId);
        if (rules.size() == 0) {
            s_logger.debug("There are no firewall rules to apply for network id=" + networkId);
            return true;
        }

        if (caller != null) {
            _accountMgr.checkAccess(caller, rules.toArray(new PortForwardingRuleVO[rules.size()]));
        }
        
        try {
            if (!applyRules(rules, continueOnError)) {
                return false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to apply firewall rules due to ", ex);
            return false;
        }
        
        return true;
    }
    
    private boolean applyRules(List<PortForwardingRuleVO> rules, boolean continueOnError) throws ResourceUnavailableException{
        if (!_networkMgr.applyRules(rules, continueOnError)) {
            s_logger.warn("Rules are not completely applied");
            return false;
        } else {
            for (PortForwardingRuleVO rule : rules) {
                if (rule.getState() == FirewallRule.State.Revoke) {
                    _forwardingDao.remove(rule.getId());
                } else if (rule.getState() == FirewallRule.State.Add) {
                    rule.setState(FirewallRule.State.Active);
                    _forwardingDao.update(rule.getId(), rule);
                }
            }
            return true;
        }
    }
    
    @Override
    public List<PortForwardingRuleVO> searchForIpForwardingRules(Long ipId, Long id, Long vmId, Long start, Long size, String accountName, Long domainId) {
        Account caller = UserContext.current().getCaller();
        String path = null;
         
        Pair<String, Long> accountDomainPair = _accountMgr.finalizeAccountDomainForList(caller, accountName, domainId);
        accountName = accountDomainPair.first();
        domainId = accountDomainPair.second();
         
         if(ipId != null){
             IPAddressVO ipAddressVO = _ipAddressDao.findById(ipId);
             if (ipAddressVO == null || !ipAddressVO.readyToUse()) {
                 throw new InvalidParameterValueException("Ip address id=" + ipId + " not ready for port forwarding rules yet");
             }
             _accountMgr.checkAccess(caller, ipAddressVO);
         }
         
         if (caller.getType() == Account.ACCOUNT_TYPE_DOMAIN_ADMIN) {
             Domain domain = _accountMgr.getDomain(caller.getDomainId());
             path = domain.getPath();
         }
         
         Filter filter = new Filter(PortForwardingRuleVO.class, "id", false, start, size); 
         SearchBuilder<PortForwardingRuleVO> sb = _forwardingDao.createSearchBuilder();
         sb.and("ip", sb.entity().getSourceIpAddressId(), Op.EQ);
         sb.and("accountId", sb.entity().getAccountId(), Op.EQ);
         sb.and("domainId", sb.entity().getDomainId(), Op.EQ);
         sb.and("oneToOneNat", sb.entity().isOneToOneNat(), Op.EQ);
         
         if (path != null) {
             //for domain admin we should show only subdomains information
             SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
             domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
             sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
         }
         
         SearchCriteria<PortForwardingRuleVO> sc = sb.create();
         
         if (ipId != null) {
             sc.setParameters("ip", ipId);
         }
         
         if (domainId != null) {
             sc.setParameters("domainId", domainId);
             if (accountName != null) {
                 Account account = _accountMgr.getActiveAccount(accountName, domainId);
                 sc.setParameters("accountId", account.getId());
             }
         }
         
         sc.setParameters("oneToOneNat", true);
         
         if (path != null) {
             sc.setJoinParameters("domainSearch", "path", path + "%");
         }
         
         return _forwardingDao.search(sc, filter);
    }
    
    @Override @ActionEvent (eventType=EventTypes.EVENT_NET_RULE_ADD, eventDescription="applying forwarding rule", async=true)
    public boolean applyPortForwardingRules(long ipId, Account caller) throws ResourceUnavailableException {
        return applyPortForwardingRules(ipId, false, caller);
    }
    
    @Override
    public boolean revokeAllRules(long ipId, long userId) throws ResourceUnavailableException {
        List<PortForwardingRuleVO> rules = _forwardingDao.listByIpAndNotRevoked(ipId);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + rules.size() + " rules for ip id=" + ipId);
        }

        for (PortForwardingRuleVO rule : rules) {
            revokeRule(rule, null, userId);
        }
      
        applyPortForwardingRules(ipId, true, null);
        
        // Now we check again in case more rules have been inserted.
        rules = _forwardingDao.listByIpAndNotRevoked(ipId);
        
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Successfully released rules for ip id=" + ipId + " and # of rules now = " + rules.size());
        }
        
        return rules.size() == 0;
    }
    
    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _name = name;
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public List<? extends FirewallRule> listFirewallRulesByIp(long ipId) {
        return null;
    }
    
    @Override
    public boolean releasePorts(long ipId, String protocol, FirewallRule.Purpose purpose, int... ports) {
        return _firewallDao.releasePorts(ipId, protocol, purpose, ports); 
    }
    
    @Override @DB
    public FirewallRuleVO[] reservePorts(IpAddress ip, String protocol, FirewallRule.Purpose purpose, int... ports) throws NetworkRuleConflictException {
        FirewallRuleVO[] rules = new FirewallRuleVO[ports.length];
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        for (int i = 0; i < ports.length; i++) {
            rules[i] = 
                new FirewallRuleVO(null,
                        ip.getId(),
                        ports[i],
                        protocol,
                        ip.getAssociatedWithNetworkId(),
                        ip.getAllocatedToAccountId(),
                        ip.getAllocatedInDomainId(),
                        purpose, ip.isOneToOneNat());
            rules[i] = _firewallDao.persist(rules[i]);
        }
        txn.commit();

        boolean success = false;
        try {
            for (FirewallRuleVO newRule : rules) {
                detectRulesConflict(newRule, ip);
            }
            success = true;
            return rules;
        } finally {
            if (!success) {
                txn.start();
                
                for (FirewallRuleVO newRule : rules) {
                    _forwardingDao.remove(newRule.getId());
                }
                txn.commit();
            }
        } 
    }
    
    @Override
    public List<? extends PortForwardingRule> gatherPortForwardingRulesForApplication(List<? extends IpAddress> addrs) {
        List<PortForwardingRuleVO> allRules = new ArrayList<PortForwardingRuleVO>();
        
        for (IpAddress addr : addrs) {
            if (!addr.readyToUse()) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Skipping " + addr + " because it is not ready for propation yet.");
                }
                continue;
            }
            allRules.addAll(_forwardingDao.listForApplication(addr.getId()));
        }
        
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Found " + allRules.size() + " rules to apply for the addresses.");
        }
        
        return allRules;
    }
    
    @Override
    public List<PortForwardingRuleVO> listByNetworkId(long networkId) {
        return _forwardingDao.listByNetworkId(networkId);
    }
    
    public boolean isLastOneToOneNatRule(FirewallRule ruleToCheck) {
        List<FirewallRuleVO> rules = _firewallDao.listByIpAndNotRevoked(ruleToCheck.getSourceIpAddressId(), false);
        if (rules != null && !rules.isEmpty()) {
            for (FirewallRuleVO rule : rules) {
                if (ruleToCheck.getId() == rule.getId()) {
                    continue;
                }
                if (rule.isOneToOneNat()) {
                    return false;
                }
            }
        } else {
            return true;
        }
        
        return true;
    }
    
    @Override
    public boolean disableOneToOneNat(long ipId){
        Account caller = UserContext.current().getCaller();
        
        IPAddressVO ipAddress = _ipAddressDao.findById(ipId);
        checkIpAndUserVm(ipAddress, null, caller);
        
        if (!ipAddress.isOneToOneNat()) {
            throw new InvalidParameterValueException("One to one nat is not enabled for the ip id=" + ipId);
        }
       
        List<FirewallRuleVO> rules = _firewallDao.listByIpAndNotRevoked(ipId, true);
        if (rules != null) {
            for (FirewallRuleVO rule : rules) {
                rule.setState(State.Revoke);
                _firewallDao.update(rule.getId(), rule);
                UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_RULE_DELETE, rule.getAccountId(), 0, rule.getId(), null);
                _usageEventDao.persist(usageEvent);
            }
        }
        
        if (applyPortForwardingRules(ipId, true, caller)) {
            ipAddress.setOneToOneNat(false);
            ipAddress.setAssociatedWithVmId(null);
            _ipAddressDao.update(ipAddress.getId(), ipAddress);
            return true;
        } else {
            s_logger.warn("Failed to disable one to one nat for the ip address id" + ipId);
            return false;
        }
    }
    
    @Override
    public List<PortForwardingRuleTO> buildPortForwardingTOrules(List<? extends PortForwardingRule> pfRules) {
        if (pfRules != null) {
            List<PortForwardingRuleTO> rulesTO = new ArrayList<PortForwardingRuleTO>();
            for (PortForwardingRule rule : pfRules) {
                IpAddress sourceIp = _networkMgr.getIp(rule.getSourceIpAddressId());
                PortForwardingRuleTO ruleTO = new PortForwardingRuleTO(rule, sourceIp.getAddress().addr());
                rulesTO.add(ruleTO);
            }
            return rulesTO;
        } else {
            return null;
        }
    }
    
    @Override
    public PortForwardingRule getPortForwardigRule(long ruleId) {
        return _forwardingDao.findById(ruleId);
    }
}
