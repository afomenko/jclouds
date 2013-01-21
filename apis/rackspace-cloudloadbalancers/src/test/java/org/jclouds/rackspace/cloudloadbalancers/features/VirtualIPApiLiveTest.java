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
package org.jclouds.rackspace.cloudloadbalancers.features;

import static org.jclouds.rackspace.cloudloadbalancers.predicates.LoadBalancerPredicates.awaitAvailable;
import static org.jclouds.rackspace.cloudloadbalancers.predicates.LoadBalancerPredicates.awaitDeleted;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.jclouds.rackspace.cloudloadbalancers.domain.LoadBalancer;
import org.jclouds.rackspace.cloudloadbalancers.domain.LoadBalancerRequest;
import org.jclouds.rackspace.cloudloadbalancers.domain.NodeRequest;
import org.jclouds.rackspace.cloudloadbalancers.domain.VirtualIP;
import org.jclouds.rackspace.cloudloadbalancers.domain.VirtualIP.Type;
import org.jclouds.rackspace.cloudloadbalancers.domain.VirtualIPWithId;
import org.jclouds.rackspace.cloudloadbalancers.internal.BaseCloudLoadBalancersApiLiveTest;
import org.testng.annotations.AfterGroups;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

/**
 * @author Everett Toews
 */
@Test(groups = "live", singleThreaded = true, testName = "VirtualIPApiLiveTest")
public class VirtualIPApiLiveTest extends BaseCloudLoadBalancersApiLiveTest {
   private LoadBalancer lb;
   private String zone;

   public void testCreateLoadBalancer() {
      NodeRequest nodeRequest = NodeRequest.builder().address("192.168.1.1").port(8080).build();
      LoadBalancerRequest lbRequest = LoadBalancerRequest.builder()
            .name(prefix+"-jclouds").protocol("HTTP").port(80).virtualIPType(Type.PUBLIC).node(nodeRequest).build(); 

      zone = Iterables.getFirst(clbApi.getConfiguredZones(), null);
      lb = clbApi.getLoadBalancerApiForZone(zone).create(lbRequest);
      
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
   }

   @Test(dependsOnMethods = "testCreateLoadBalancer")
   public void testCreateVirtualIPs() throws Exception {
      clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).create(VirtualIP.publicIPv6());
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).create(VirtualIP.publicIPv6());
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).create(VirtualIP.publicIPv6());
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));

      Iterable<VirtualIPWithId> actualVirtualIPs = clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).list();

      assertEquals(Iterators.size(actualVirtualIPs.iterator()), 5);
   }
   
   @Test(dependsOnMethods = "testCreateVirtualIPs")
   public void testRemoveSingleVirtualIP() throws Exception {
      Iterable<VirtualIPWithId> actualVirtualIPs = clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).list();
      VirtualIPWithId removedVirtualIP = Iterables.getFirst(actualVirtualIPs, null);
      
      assertTrue(clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).remove(removedVirtualIP.getId()));
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      
      actualVirtualIPs = clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).list();

      assertEquals(Iterators.size(actualVirtualIPs.iterator()), 4);
   }
   
   @Test(dependsOnMethods = "testRemoveSingleVirtualIP")
   public void testRemoveManyVirtualIPs() throws Exception {
      Iterable<VirtualIPWithId> actualVirtualIPs = clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).list();
      VirtualIPWithId removedVirtualIP1 = Iterables.getFirst(actualVirtualIPs, null);
      VirtualIPWithId removedVirtualIP2 = Iterables.getLast(actualVirtualIPs);
      List<Integer> removedVirtualIPIds = ImmutableList.<Integer> of(removedVirtualIP1.getId(), removedVirtualIP2.getId());
      
      assertTrue(clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).remove(removedVirtualIPIds));
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      
      actualVirtualIPs = clbApi.getVirtualIPApiForZoneAndLoadBalancer(zone, lb.getId()).list();

      assertEquals(Iterators.size(actualVirtualIPs.iterator()), 2);
   }

   @Override
   @AfterGroups(groups = "live")
   protected void tearDownContext() {
      assertTrue(awaitAvailable(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      clbApi.getLoadBalancerApiForZone(zone).remove(lb.getId());
      assertTrue(awaitDeleted(clbApi.getLoadBalancerApiForZone(zone)).apply(lb));
      super.tearDownContext();
   }
}
