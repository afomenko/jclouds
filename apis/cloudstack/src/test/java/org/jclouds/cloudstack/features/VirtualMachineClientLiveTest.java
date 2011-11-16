/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.cloudstack.features;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.or;
import static com.google.common.collect.Iterables.find;
import static com.google.common.collect.Iterables.get;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Sets.filter;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.jclouds.cloudstack.CloudStackClient;
import org.jclouds.cloudstack.domain.AsyncCreateResponse;
import org.jclouds.cloudstack.domain.AsyncJob;
import org.jclouds.cloudstack.domain.GuestIPType;
import org.jclouds.cloudstack.domain.NIC;
import org.jclouds.cloudstack.domain.Network;
import org.jclouds.cloudstack.domain.NetworkOffering;
import org.jclouds.cloudstack.domain.ServiceOffering;
import org.jclouds.cloudstack.domain.Template;
import org.jclouds.cloudstack.domain.VirtualMachine;
import org.jclouds.cloudstack.domain.Zone;
import org.jclouds.cloudstack.options.CreateNetworkOptions;
import org.jclouds.cloudstack.options.DeployVirtualMachineOptions;
import org.jclouds.cloudstack.options.ListNetworkOfferingsOptions;
import org.jclouds.cloudstack.options.ListNetworksOptions;
import org.jclouds.cloudstack.options.ListTemplatesOptions;
import org.jclouds.cloudstack.options.ListVirtualMachinesOptions;
import org.jclouds.net.IPSocket;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.util.InetAddresses2;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import com.google.common.net.HostSpecifier;

import javax.annotation.Nullable;

/**
 * Tests behavior of {@code VirtualMachineClientLiveTest}
 * 
 * @author Adrian Cole
 */
@Test(groups = "live", singleThreaded = true, testName = "VirtualMachineClientLiveTest")
public class VirtualMachineClientLiveTest extends BaseCloudStackClientLiveTest {
   private final static Logger logger = Logger.getAnonymousLogger();

   private VirtualMachine vm = null;

   static final Ordering<ServiceOffering> DEFAULT_SIZE_ORDERING = new Ordering<ServiceOffering>() {
      public int compare(ServiceOffering left, ServiceOffering right) {
         return ComparisonChain.start().compare(left.getCpuNumber(), right.getCpuNumber())
               .compare(left.getMemory(), right.getMemory()).result();
      }
   };

   public static VirtualMachine createVirtualMachine(CloudStackClient client, Long defaultTemplate,
         RetryablePredicate<Long> jobComplete, RetryablePredicate<VirtualMachine> virtualMachineRunning) {
      Set<Network> networks = client.getNetworkClient().listNetworks();
      if (networks.size() > 0) {
         Network network = get(networks, 0);
         return createVirtualMachineInNetwork(network,
               defaultTemplateOrPreferredInZone(defaultTemplate, client, network.getZoneId()), client, jobComplete,
               virtualMachineRunning);
      } else {
         long zoneId = find(client.getZoneClient().listZones(), new Predicate<Zone>() {

            @Override
            public boolean apply(Zone arg0) {
               return arg0.isSecurityGroupsEnabled();
            }

         }).getId();
         return createVirtualMachineWithSecurityGroupInZone(zoneId,
               defaultTemplateOrPreferredInZone(defaultTemplate, client, zoneId),
               get(client.getSecurityGroupClient().listSecurityGroups(), 0).getId(), client, jobComplete,
               virtualMachineRunning);
      }
   }

   public static VirtualMachine createVirtualMachineWithSecurityGroupInZone(long zoneId, long templateId, long groupId,
         CloudStackClient client, RetryablePredicate<Long> jobComplete,
         RetryablePredicate<VirtualMachine> virtualMachineRunning) {
      return createVirtualMachineWithOptionsInZone(new DeployVirtualMachineOptions().securityGroupId(groupId), zoneId,
            templateId, client, jobComplete, virtualMachineRunning);
   }

   public static VirtualMachine createVirtualMachineInNetwork(Network network, long templateId,
         CloudStackClient client, RetryablePredicate<Long> jobComplete,
         RetryablePredicate<VirtualMachine> virtualMachineRunning) {
      DeployVirtualMachineOptions options = new DeployVirtualMachineOptions();
      long zoneId = network.getZoneId();
      options.networkId(network.getId());
      return createVirtualMachineWithOptionsInZone(options, zoneId, templateId, client, jobComplete,
            virtualMachineRunning);
   }

   public static VirtualMachine createVirtualMachineInNetworkWithIp(
         CloudStackClient client, long templateId, Set<Network> networks, Map<String, Long> ipToNetwork,
         RetryablePredicate<Long> jobComplete, RetryablePredicate<VirtualMachine> virtualMachineRunning) {

      DeployVirtualMachineOptions options = new DeployVirtualMachineOptions();

      long zoneId = getFirst(networks, null).getZoneId();
      options.networkIds(Iterables.transform(networks, new Function<Network, Long>() {
         @Override
         public Long apply(@Nullable Network network) {
            return network.getId();
         }
      }));
      options.ipToNetworkList(ipToNetwork);

      return createVirtualMachineWithOptionsInZone(options, zoneId, templateId,
         client, jobComplete, virtualMachineRunning);
   }

   public static VirtualMachine createVirtualMachineWithOptionsInZone(DeployVirtualMachineOptions options, long zoneId,
         long templateId, CloudStackClient client, RetryablePredicate<Long> jobComplete,
         RetryablePredicate<VirtualMachine> virtualMachineRunning) {
      long serviceOfferingId = DEFAULT_SIZE_ORDERING.min(client.getOfferingClient().listServiceOfferings()).getId();

      System.out.printf("serviceOfferingId %d, templateId %d, zoneId %d, options %s%n", serviceOfferingId, templateId,
            zoneId, options);
      AsyncCreateResponse job = client.getVirtualMachineClient().deployVirtualMachineInZone(zoneId, serviceOfferingId,
            templateId, options);
      assert jobComplete.apply(job.getJobId());
      AsyncJob<VirtualMachine> jobWithResult = client.getAsyncJobClient().<VirtualMachine> getAsyncJob(job.getJobId());
      if (jobWithResult.getError() != null)
         Throwables.propagate(new ExecutionException(String.format("job %s failed with exception %s", job.getId(),
               jobWithResult.getError().toString())) {
            private static final long serialVersionUID = 4371112085613620239L;
         });
      VirtualMachine vm = jobWithResult.getResult();
      if (vm.isPasswordEnabled()) {
         assert vm.getPassword() != null : vm;
      }
      assert virtualMachineRunning.apply(vm);
      assertEquals(vm.getServiceOfferingId(), serviceOfferingId);
      assertEquals(vm.getTemplateId(), templateId);
      assertEquals(vm.getZoneId(), zoneId);
      return vm;
   }

   @SuppressWarnings("unchecked")
   public void testCreateVirtualMachine() throws Exception {
      Long templateId = (imageId != null && !"".equals(imageId)) ? new Long(imageId) : null;
      vm = createVirtualMachine(client, templateId, jobComplete, virtualMachineRunning);
      if (vm.getPassword() != null) {
         conditionallyCheckSSH();
      }
      assert in(ImmutableSet.of("NetworkFilesystem", "IscsiLUN", "VMFS", "PreSetup"))
         .apply(vm.getRootDeviceType()) : vm;
      checkVm(vm);
   }

   public void testCreateVirtualMachineWithSpecificIp() throws Exception {
      Long templateId = (imageId != null && !"".equals(imageId)) ? new Long(imageId) : null;
      Network network = null;

      try {
         Template template = getOnlyElement(
            client.getTemplateClient().listTemplates(ListTemplatesOptions.Builder.id(templateId)));
         logger.info("Using template: " + template);

         Set<Network> allSafeNetworksInZone = adminClient.getNetworkClient().listNetworks(
            ListNetworksOptions.Builder.zoneId(template.getZoneId()).isSystem(false));
         for(Network net : allSafeNetworksInZone) {
            if(net.getName().equals(prefix + "-ip-network")) {
               logger.info("Deleting VMs in network: " + net);

               Set<VirtualMachine> machinesInNetwork = adminClient.getVirtualMachineClient().listVirtualMachines(
                  ListVirtualMachinesOptions.Builder.networkId(net.getId()));

               for(VirtualMachine machine : machinesInNetwork) {
                  if (machine.getState().equals(VirtualMachine.State.RUNNING)) {
                     logger.info("Deleting VM: " + machine);
                     destroyMachine(machine);
                  }
               }

               assert adminJobComplete.apply(
                  adminClient.getNetworkClient().deleteNetwork(net.getId())) : net;
            }
         }

         NetworkOffering offering = getFirst(
            client.getOfferingClient().listNetworkOfferings(
               ListNetworkOfferingsOptions.Builder.zoneId(template.getZoneId()).specifyVLAN(true)), null);
         checkNotNull(offering, "No network offering found");
         logger.info("Using network offering: " + offering);

         network = adminClient.getNetworkClient().createNetworkInZone(
            template.getZoneId(), offering.getId(), prefix + "-ip-network", "",
            CreateNetworkOptions.Builder.startIP("192.168.0.1").endIP("192.168.0.5")
               .netmask("255.255.255.0").gateway("192.168.0.1").vlan("21"));
         logger.info("Created network: " + network);

         Network requiredNetwork = getOnlyElement(filter(adminClient.getNetworkClient().listNetworks(
            ListNetworksOptions.Builder.zoneId(template.getZoneId())), new Predicate<Network>() {
            @Override
            public boolean apply(@Nullable Network network) {
               return network.isDefault() &&
                  network.getGuestIPType() == GuestIPType.VIRTUAL &&
                  network.getNetworkOfferingId() == 6 &&
                  network.getId() == 204;
            }
         }));
         logger.info("Required network: " + requiredNetwork);

         String ipAddress = "192.168.0.4";

         Map<String, Long> ipToNetwork = Maps.newHashMap();
         ipToNetwork.put(ipAddress, network.getId());

         vm = createVirtualMachineInNetworkWithIp(
            adminClient, templateId, ImmutableSet.of(requiredNetwork, network),
            ipToNetwork, adminJobComplete, adminVirtualMachineRunning);
         logger.info("Created VM: " + vm);

         boolean hasStaticIpNic = false;
         for(NIC nic : vm.getNICs()) {
            if (nic.getNetworkId() == network.getId()) {
               hasStaticIpNic = true;
               assertEquals(nic.getIPAddress(), ipAddress);
            }
         }
         assert hasStaticIpNic;
         checkVm(vm);

      } finally {
         if (vm != null) {
            destroyMachine(vm);
            vm = null;
         }
         if (network != null) {
            long jobId = adminClient.getNetworkClient().deleteNetwork(network.getId());
            adminJobComplete.apply(jobId);
            network = null;
         }
      }
   }

   private void destroyMachine(VirtualMachine virtualMachine) {
      assert adminJobComplete.apply(
         adminClient.getVirtualMachineClient().destroyVirtualMachine(virtualMachine.getId())) : virtualMachine;
      assert adminVirtualMachineDestroyed.apply(virtualMachine);
   }

   private void conditionallyCheckSSH() {
      password = vm.getPassword();
      assert HostSpecifier.isValid(vm.getIPAddress());
      if (!InetAddresses2.isPrivateIPAddress(vm.getIPAddress())) {
         // not sure if the network is public or not, so we have to test
         IPSocket socket = new IPSocket(vm.getIPAddress(), 22);
         System.err.printf("testing socket %s%n", socket);
         System.err.printf("testing ssh %s%n", socket);
         this.checkSSH(socket);
      } else {
         System.err.printf("skipping ssh %s, as private%n", vm.getIPAddress());
      }
   }

   @Test(dependsOnMethods = "testCreateVirtualMachine")
   public void testLifeCycle() throws Exception {
      Long job = client.getVirtualMachineClient().stopVirtualMachine(vm.getId());
      assert jobComplete.apply(job);
      vm = client.getVirtualMachineClient().getVirtualMachine(vm.getId());
      assertEquals(vm.getState(), VirtualMachine.State.STOPPED);

      if (vm.isPasswordEnabled()) {
         job = client.getVirtualMachineClient().resetPasswordForVirtualMachine(vm.getId());
         assert jobComplete.apply(job);
         vm = client.getAsyncJobClient().<VirtualMachine> getAsyncJob(job).getResult();
         if (vm.getPassword() != null) {
            conditionallyCheckSSH();
         }
      }

      job = client.getVirtualMachineClient().startVirtualMachine(vm.getId());
      assert jobComplete.apply(job);
      vm = client.getVirtualMachineClient().getVirtualMachine(vm.getId());
      assertEquals(vm.getState(), VirtualMachine.State.RUNNING);

      job = client.getVirtualMachineClient().rebootVirtualMachine(vm.getId());
      assert jobComplete.apply(job);
      vm = client.getVirtualMachineClient().getVirtualMachine(vm.getId());
      assertEquals(vm.getState(), VirtualMachine.State.RUNNING);
   }

   @AfterGroups(groups = "live")
   protected void tearDown() {
      if (vm != null) {
         destroyMachine(vm);
         vm = null;
      }
      super.tearDown();
   }

   public void testListVirtualMachines() throws Exception {
      Set<VirtualMachine> response = client.getVirtualMachineClient().listVirtualMachines();
      assert null != response;
      assertTrue(response.size() >= 0);
      for (VirtualMachine vm : response) {
         VirtualMachine newDetails = getOnlyElement(client.getVirtualMachineClient().listVirtualMachines(
               ListVirtualMachinesOptions.Builder.id(vm.getId())));
         assertEquals(vm.getId(), newDetails.getId());
         checkVm(vm);
      }
   }

   protected void checkVm(VirtualMachine vm) {
      assertEquals(vm.getId(), client.getVirtualMachineClient().getVirtualMachine(vm.getId()).getId());
      assert vm.getId() > 0 : vm;
      assert vm.getName() != null : vm;
      assert vm.getDisplayName() != null : vm;
      assert vm.getAccount() != null : vm;
      assert vm.getDomain() != null : vm;
      assert vm.getDomainId() > 0 : vm;
      assert vm.getCreated() != null : vm;
      assert vm.getState() != null : vm;
      assert vm.getZoneId() > 0 : vm;
      assert vm.getZoneName() != null : vm;
      assert vm.getTemplateId() > 0 : vm;
      assert vm.getTemplateName() != null : vm;
      assert vm.getServiceOfferingId() > 0 : vm;
      assert vm.getServiceOfferingName() != null : vm;
      assert vm.getCpuCount() > 0 : vm;
      assert vm.getCpuSpeed() > 0 : vm;
      assert vm.getMemory() > 0 : vm;
      assert vm.getGuestOSId() > 0 : vm;
      assert vm.getRootDeviceId() >= 0 : vm;
      // assert vm.getRootDeviceType() != null : vm;
      if (vm.getJobId() != null)
         assert vm.getJobStatus() != null : vm;
      assert vm.getNICs() != null && vm.getNICs().size() > 0 : vm;
      for (NIC nic : vm.getNICs()) {
         assert nic.getId() > 0 : vm;
         assert nic.getNetworkId() > 0 : vm;
         assert nic.getTrafficType() != null : vm;
         assert nic.getGuestIPType() != null : vm;
         switch (vm.getState()) {
         case RUNNING:
            assert nic.getNetmask() != null : vm;
            assert nic.getGateway() != null : vm;
            assert nic.getIPAddress() != null : vm;
            break;
         case STARTING:
            assert nic.getNetmask() == null : vm;
            assert nic.getGateway() == null : vm;
            assert nic.getIPAddress() == null : vm;
            break;
         default:
            assert nic.getNetmask() != null : vm;
            assert nic.getGateway() != null : vm;
            assert nic.getIPAddress() != null : vm;
         }

      }
      assert vm.getSecurityGroups() != null && vm.getSecurityGroups().size() >= 0 : vm;
      assert vm.getHypervisor() != null : vm;
   }
}