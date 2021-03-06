/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.deploy.handlers;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.model.*;
import com.netflix.spinnaker.clouddriver.aws.AwsConfiguration.DeployDefaults;
import com.netflix.spinnaker.clouddriver.aws.deploy.description.UpsertSecurityGroupDescription;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.LoadBalancerMigrator.LoadBalancerLocation;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.MigrateLoadBalancerResult;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.MigrateSecurityGroupReference;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.MigrateSecurityGroupResult;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupLookupFactory.SecurityGroupLookup;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupMigrator;
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.securitygroup.SecurityGroupMigrator.SecurityGroupLocation;
import com.netflix.spinnaker.clouddriver.aws.model.SubnetTarget;
import com.netflix.spinnaker.clouddriver.aws.provider.view.AmazonVpcProvider;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials;
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory;
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class MigrateLoadBalancerStrategy implements MigrateStrategySupport {

  protected SecurityGroupLookup sourceLookup;
  protected SecurityGroupLookup targetLookup;
  protected MigrateSecurityGroupStrategy migrateSecurityGroupStrategy;

  protected LoadBalancerLocation source;
  protected LoadBalancerLocation target;
  protected String subnetType;
  protected String applicationName;
  protected boolean allowIngressFromClassic;
  protected boolean dryRun;

  abstract AmazonClientProvider getAmazonClientProvider();

  abstract RegionScopedProviderFactory getRegionScopedProviderFactory();

  abstract DeployDefaults getDeployDefaults();

  /**
   * Generates a result set describing the actions required to migrate the source load balancer to the target.
   *
   * @param sourceLookup            a security group lookup cache for the source region
   * @param targetLookup            a security group lookup cache for the target region (may be the same object as the sourceLookup)
   * @param source                  the source load balancer
   * @param target                  the target location
   * @param subnetType              the subnetType in which to migrate the load balancer (should be null for EC Classic migrations)
   * @param applicationName         the name of the source application
   * @param allowIngressFromClassic whether ingress should be granted from classic link
   * @param dryRun                  whether to actually perform the migration
   * @return the result set
   */
  public synchronized MigrateLoadBalancerResult generateResults(SecurityGroupLookup sourceLookup, SecurityGroupLookup targetLookup,
                                                                MigrateSecurityGroupStrategy migrateSecurityGroupStrategy,
                                                                LoadBalancerLocation source, LoadBalancerLocation target,
                                                                String subnetType, String applicationName,
                                                                boolean allowIngressFromClassic, boolean dryRun) {

    this.migrateSecurityGroupStrategy = migrateSecurityGroupStrategy;
    this.sourceLookup = sourceLookup;
    this.targetLookup = targetLookup;
    this.source = source;
    this.target = target;
    this.subnetType = subnetType;
    this.applicationName = applicationName;
    this.allowIngressFromClassic = allowIngressFromClassic;
    this.dryRun = dryRun;

    final MigrateLoadBalancerResult result = new MigrateLoadBalancerResult();

    LoadBalancerDescription sourceLoadBalancer = getLoadBalancer(source.getCredentials(), source.getRegion(), source.getName());
    Vpc sourceVpc = getVpc(source);
    Vpc targetVpc = getVpc(target);

    String targetName = target.getName() != null ? target.getName() : generateLoadBalancerName(source.getName(), sourceVpc, targetVpc);
    LoadBalancerDescription targetLoadBalancer = getLoadBalancer(target.getCredentials(), target.getRegion(), targetName);

    List<MigrateSecurityGroupResult> targetGroups = getTargetSecurityGroups(sourceLoadBalancer, result);

    List<String> securityGroups = targetGroups.stream().map(g -> g.getTarget().getTargetId()).distinct().collect(Collectors.toList());
    securityGroups.addAll(buildExtraSecurityGroups(sourceLoadBalancer, result));

    result.getSecurityGroups().addAll(targetGroups);
    result.setTargetName(targetName);
    result.setTargetExists(targetLoadBalancer != null);
    if (!dryRun) {
      updateTargetLoadBalancer(sourceLoadBalancer, targetLoadBalancer, targetName, securityGroups, result);
    }

    return result;
  }

  /**
   * Performs the actual upsert operation against the target load balancer
   *
   * @param sourceLoadBalancer the Amazon load balancer description of the source load balancer
   * @param targetLoadBalancer the Amazon load balancer description of the target load balancer (may be null)
   * @param targetName         the name of the target load balancer
   * @param securityGroups     a list of security group names to attach to the load balancer
   */
  protected void updateTargetLoadBalancer(LoadBalancerDescription sourceLoadBalancer,
                                          LoadBalancerDescription targetLoadBalancer,
                                          String targetName, Collection<String> securityGroups,
                                          MigrateLoadBalancerResult result) {

    List<Listener> unmigratableListeners = sourceLoadBalancer.getListenerDescriptions().stream()
      .map(ListenerDescription::getListener)
      .filter(listenerCannotBeMigrated(source, target)).collect(Collectors.toList());

    unmigratableListeners.forEach(l -> result.getWarnings().add(
      "The following listeners could not be created: " +
        l.getProtocol() + ":" + l.getLoadBalancerPort() + " => " +
        l.getInstanceProtocol() + ":" + l.getInstancePort() + " (certificate: " + l.getSSLCertificateId() + ")."
    ));

    List<Listener> listeners = sourceLoadBalancer.getListenerDescriptions().stream()
      .map(ListenerDescription::getListener)
      .collect(Collectors.toList());

    listeners.removeAll(unmigratableListeners);

    List<String> subnetIds = subnetType != null ?
      getRegionScopedProviderFactory().forRegion(target.getCredentials(), target.getRegion())
        .getSubnetAnalyzer().getSubnetIdsForZones(target.getAvailabilityZones(), subnetType, SubnetTarget.ELB, 1) :
      new ArrayList();
    AmazonElasticLoadBalancing client = getAmazonClientProvider()
      .getAmazonElasticLoadBalancing(target.getCredentials(), target.getRegion(), true);
    if (targetLoadBalancer == null) {
      boolean isInternal = subnetType == null || subnetType.contains("internal");
      LoadBalancerUpsertHandler.createLoadBalancer(
        client, targetName, isInternal, target.getAvailabilityZones(), subnetIds, listeners, securityGroups);
      configureHealthCheck(client, sourceLoadBalancer, targetName);
    } else {
      LoadBalancerUpsertHandler.updateLoadBalancer(client, targetLoadBalancer, listeners, securityGroups);
    }
  }

  private void configureHealthCheck(AmazonElasticLoadBalancing loadBalancing,
                                    LoadBalancerDescription source, String loadBalancerName) {
    HealthCheck healthCheck = new HealthCheck()
      .withTarget(source.getHealthCheck().getTarget())
      .withInterval(source.getHealthCheck().getInterval())
      .withTimeout(source.getHealthCheck().getTimeout())
      .withUnhealthyThreshold(source.getHealthCheck().getUnhealthyThreshold())
      .withHealthyThreshold(source.getHealthCheck().getHealthyThreshold());

    loadBalancing.configureHealthCheck(new ConfigureHealthCheckRequest(loadBalancerName, healthCheck));
  }

  private Predicate<Listener> listenerCannotBeMigrated(LoadBalancerLocation source, LoadBalancerLocation target) {
    return l -> l.getSSLCertificateId() != null && !source.getCredentialAccount().equals(target.getCredentialAccount());
  }

  private LoadBalancerDescription getLoadBalancer(NetflixAmazonCredentials credentials, String region, String name) {
    try {
      AmazonElasticLoadBalancing client = getAmazonClientProvider()
        .getAmazonElasticLoadBalancing(credentials, region, true);
      DescribeLoadBalancersResult targetLookup = client.describeLoadBalancers(
        new DescribeLoadBalancersRequest().withLoadBalancerNames(name));
      return targetLookup.getLoadBalancerDescriptions().get(0);
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Generates a list of security groups to add to the load balancer in addition to those on the source load balancer
   *
   * @param sourceDescription       the AWS description of the source load balancer
   * @param result                  the result set for the load balancer migration - this will potentially be mutated as a side effect
   * @return a list security group ids that should be added to the load balancer
   */
  protected List<String> buildExtraSecurityGroups(LoadBalancerDescription sourceDescription,
                                                  MigrateLoadBalancerResult result) {
    ArrayList<String> newGroups = new ArrayList<>();
    if (target.getVpcId() != null) {
      AmazonEC2 targetAmazonEC2 = getAmazonClientProvider().getAmazonEC2(target.getCredentials(), target.getRegion(), true);
      List<SecurityGroup> appGroups = new ArrayList<>();
      try {
        List<String> groupNames = Arrays.asList(applicationName, applicationName + "-elb");
        appGroups = targetAmazonEC2.describeSecurityGroups(new DescribeSecurityGroupsRequest().withFilters(
          new Filter("group-name", groupNames))).getSecurityGroups();
      } catch (Exception ignored) {
      }

      String elbGroupId = buildElbSecurityGroup(sourceDescription, appGroups, result);
      newGroups.add(elbGroupId);
    }
    return newGroups;
  }

  /**
   * Creates an elb specific security group, or returns the ID of one if it already exists
   *
   * @param sourceDescription       the AWS description of the source load balancer
   * @param appGroups               list of existing security groups in which to look for existing elb security group
   * @param result                  the result set for the load balancer migration - this will potentially be mutated as a side effect
   * @return the groupId of the elb security group
   */
  protected String buildElbSecurityGroup(LoadBalancerDescription sourceDescription, List<SecurityGroup> appGroups,
                                         MigrateLoadBalancerResult result) {
    String elbGroupId = null;
    Optional<SecurityGroup> existingGroup = appGroups.stream()
      .filter(g -> g.getVpcId() != null && g.getVpcId().equals(target.getVpcId()) && g.getGroupName().equals(applicationName + "-elb"))
      .findFirst();
    if (existingGroup.isPresent()) {
      if (!dryRun && allowIngressFromClassic) {
        addClassicLinkIngress(targetLookup, getDeployDefaults().getClassicLinkSecurityGroupName(),
          existingGroup.get().getGroupId(), target.getCredentials(), target.getVpcId());
      }
      return existingGroup.get().getGroupId();
    }
    MigrateSecurityGroupReference elbGroup = new MigrateSecurityGroupReference();
    elbGroup.setAccountId(target.getCredentials().getAccountId());
    elbGroup.setVpcId(target.getVpcId());
    elbGroup.setTargetName(applicationName + "-elb");
    MigrateSecurityGroupResult addedGroup = new MigrateSecurityGroupResult();
    addedGroup.setTarget(elbGroup);
    addedGroup.getCreated().add(elbGroup);
    result.getSecurityGroups().add(addedGroup);
    if (!dryRun) {
      UpsertSecurityGroupDescription upsertDescription = new UpsertSecurityGroupDescription();
      upsertDescription.setDescription("Application load balancer security group for " + applicationName);
      upsertDescription.setName(applicationName + "-elb");
      upsertDescription.setVpcId(target.getVpcId());
      upsertDescription.setRegion(target.getRegion());
      upsertDescription.setCredentials(target.getCredentials());
      elbGroupId = targetLookup.createSecurityGroup(upsertDescription).getSecurityGroup().getGroupId();
      AmazonEC2 targetAmazonEC2 = getAmazonClientProvider().getAmazonEC2(target.getCredentials(), target.getRegion(), true);
      elbGroup.setTargetId(elbGroupId);
      if (source.getVpcId() == null && allowIngressFromClassic) {
        addClassicLinkIngress(targetLookup, getDeployDefaults().getClassicLinkSecurityGroupName(),
          elbGroupId, target.getCredentials(), target.getVpcId());
        addPublicIngress(targetAmazonEC2, elbGroupId, sourceDescription);
      }
    }
    if (getDeployDefaults().getAddAppGroupToServerGroup()) {
      buildApplicationSecurityGroup(sourceDescription, appGroups, addedGroup);
    }

    return elbGroupId;
  }

  /**
   * Creates the app specific security group, or returns the ID of one if it already exists
   *
   * @param appGroups               list of existing security groups in which to look for existing app security group
   * @param elbGroup                the elb specific security group, which will allow ingress permission from the
   *                                app specific security group
   */
  protected void buildApplicationSecurityGroup(LoadBalancerDescription sourceDescription, List<SecurityGroup> appGroups,
                                               MigrateSecurityGroupResult elbGroup) {
    if (getDeployDefaults().getAddAppGroupToServerGroup()) {
      AmazonEC2 targetAmazonEC2 = getAmazonClientProvider().getAmazonEC2(target.getCredentials(), target.getRegion(), true);
      Optional<SecurityGroup> existing = appGroups.stream().filter(isAppSecurityGroup()).findFirst();
      MigrateSecurityGroupReference appGroupReference = new MigrateSecurityGroupReference();
      appGroupReference.setAccountId(target.getCredentials().getAccountId());
      appGroupReference.setVpcId(target.getVpcId());
      appGroupReference.setTargetName(applicationName);
      if (existing.isPresent()) {
        elbGroup.getReused().add(appGroupReference);
      } else {
        elbGroup.getCreated().add(appGroupReference);
        if (!dryRun) {
          UpsertSecurityGroupDescription upsertDescription = new UpsertSecurityGroupDescription();
          upsertDescription.setDescription("Application security group for " + applicationName);
          upsertDescription.setName(applicationName);
          upsertDescription.setVpcId(target.getVpcId());
          upsertDescription.setRegion(target.getRegion());
          upsertDescription.setCredentials(target.getCredentials());
          String newGroupId = targetLookup.createSecurityGroup(upsertDescription).getSecurityGroup().getGroupId();
          // After the create request completes, there is a brief period where the security group might not be
          // available and subsequent operations on it will fail, so make sure it's there
          OperationPoller.retryWithBackoff(
            o -> appGroups.addAll(targetAmazonEC2.describeSecurityGroups(
              new DescribeSecurityGroupsRequest().withGroupIds(newGroupId)).getSecurityGroups()),
            200, 5);
        }
      }
      if (!dryRun) {
        String elbGroupId = elbGroup.getTarget().getTargetId();
        SecurityGroup appGroup = appGroups.stream().filter(isAppSecurityGroup()).findFirst().get();
        if (allowIngressFromClassic) {
          addClassicLinkIngress(targetLookup, getDeployDefaults().getClassicLinkSecurityGroupName(),
            appGroup.getGroupId(), target.getCredentials(), target.getVpcId());
        }
        boolean hasElbIngressPermission = appGroup.getIpPermissions().stream()
          .anyMatch(p -> p.getUserIdGroupPairs().stream().anyMatch(u -> u.getGroupId().equals(elbGroupId)));
        if (!hasElbIngressPermission) {
          sourceDescription.getListenerDescriptions().forEach(l -> {
            Listener listener = l.getListener();
            IpPermission newPermission = new IpPermission().withIpProtocol("tcp")
              .withFromPort(listener.getInstancePort()).withToPort(listener.getInstancePort())
              .withUserIdGroupPairs(new UserIdGroupPair().withGroupId(elbGroupId).withVpcId(target.getVpcId()));
            targetAmazonEC2.authorizeSecurityGroupIngress(new AuthorizeSecurityGroupIngressRequest()
              .withGroupId(appGroup.getGroupId())
              .withIpPermissions(newPermission)
            );
          });
        }
      }
    }
  }

  private Predicate<SecurityGroup> isAppSecurityGroup() {
    return g -> {
      if (!g.getGroupName().equals(applicationName)) {
        return false;
      }
      if (g.getVpcId() == null) {
        return target.getVpcId() == null;
      }
      return g.getVpcId().equals(target.getVpcId());
    };
  }

  // Adds a default public ingress for the load balancer. Called when migrating from Classic to VPC
  private void addPublicIngress(AmazonEC2 targetAmazonEC2, String elbGroupId, LoadBalancerDescription sourceDescription) {
    List<IpPermission> permissions = sourceDescription.getListenerDescriptions().stream().map(l -> new IpPermission()
      .withIpProtocol("tcp")
      .withFromPort(l.getListener().getLoadBalancerPort())
      .withToPort(l.getListener().getLoadBalancerPort())
      .withIpRanges("0.0.0.0/0")
    ).collect(Collectors.toList());

    targetAmazonEC2.authorizeSecurityGroupIngress(new AuthorizeSecurityGroupIngressRequest()
      .withGroupId(elbGroupId)
      .withIpPermissions(permissions)
    );
  }

  /**
   * Generates a list of security groups that should be applied to the target load balancer
   *
   * @param sourceDescription AWS descriptor of source load balancer
   * @param result            result object of the calling migate operation
   * @return the list of security groups that will be created or added, excluding the elb-specific security group
   */
  protected List<MigrateSecurityGroupResult> getTargetSecurityGroups(LoadBalancerDescription sourceDescription,
                                                                     MigrateLoadBalancerResult result) {
    sourceDescription.getSecurityGroups().stream()
      .filter(g -> !sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId()).isPresent())
      .forEach(m -> result.getWarnings().add("Skipping creation of security group: " + m + " (could not be found in source location)"));
    List<SecurityGroup> currentGroups = sourceDescription.getSecurityGroups().stream()
      .filter(g -> sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId()).isPresent())
      .map(g -> sourceLookup.getSecurityGroupById(source.getCredentialAccount(), g, source.getVpcId())
        .get().getSecurityGroup()).collect(Collectors.toList());

    return sourceDescription.getSecurityGroups().stream()
      .filter(g -> currentGroups.stream().anyMatch(g2 -> g2.getGroupId().equals(g)))
      .map(g -> {
        SecurityGroup match = currentGroups.stream().filter(g3 -> g3.getGroupId().equals(g)).findFirst().get();
        SecurityGroupLocation sourceLocation = new SecurityGroupLocation();
        sourceLocation.setName(match.getGroupName());
        sourceLocation.setRegion(source.getRegion());
        sourceLocation.setCredentials(source.getCredentials());
        sourceLocation.setVpcId(source.getVpcId());
        return new SecurityGroupMigrator(sourceLookup, targetLookup, migrateSecurityGroupStrategy,
          sourceLocation, new SecurityGroupLocation(target)).migrate(dryRun);
      })
      .collect(Collectors.toList());
  }

  private Vpc getVpc(LoadBalancerLocation source) {
    if (source.getVpcId() != null) {
      DescribeVpcsResult vpcLookup = getAmazonClientProvider().getAmazonEC2(source.getCredentials(), source.getRegion())
        .describeVpcs(new DescribeVpcsRequest().withVpcIds(source.getVpcId()));
      if (vpcLookup.getVpcs().isEmpty()) {
        throw new IllegalStateException(String.format("Could not find VPC %s in %s/%s",
          source.getVpcId(), source.getCredentialAccount(), source.getRegion()));
      }

      return vpcLookup.getVpcs().get(0);
    }

    return null;
  }

  /**
   * Generates the name of the new load balancer. By default, removes a number of suffixes, then adds the name
   * of the VPC (if any), and shrinks the load balancer name to 32 characters if necessary
   *
   * @param sourceName the base name
   * @param sourceVpc  the source VPC
   * @param targetVpc  the target VPC
   * @return the final name of the load balancer
   */
  protected String generateLoadBalancerName(String sourceName, Vpc sourceVpc, Vpc targetVpc) {
    String targetName = sourceName;
    targetName = removeSuffix(targetName, AmazonVpcProvider.getVpcName(sourceVpc));
    targetName = removeSuffix(targetName, "classic");
    targetName = removeSuffix(targetName, "frontend");
    targetName = removeSuffix(targetName, "vpc");
    if (targetVpc != null) {
      targetName += "-" + AmazonVpcProvider.getVpcName(targetVpc);
    }

    return shrinkName(targetName);
  }

  private String removeSuffix(String name, String suffix) {
    if (name.endsWith("-" + suffix)) {
      name = name.substring(0, name.length() - suffix.length() - 1);
    }
    return name;
  }

  /**
   * Reduces name to 32 characters
   *
   * @param name the name
   * @return the short version of the name
   */
  protected String shrinkName(String name) {
    final int MAX_LENGTH = 32;

    if (name.length() > MAX_LENGTH) {
      name = name
        .replace("-internal", "-int")
        .replace("-external", "-ext")
        .replace("-elb", "");
    }


    if (name.length() > MAX_LENGTH) {
      name = name
        .replace("-dev", "-d")
        .replace("-test", "-t")
        .replace("-prod", "-p")
        .replace("-main", "-m")
        .replace("-legacy", "-l")
        .replace("-backend", "-b")
        .replace("-front", "-f")
        .replace("-release", "-r")
        .replace("-private", "-p")
        .replace("-edge", "-e")
        .replace("-global", "-g");
    }


    if (name.length() > MAX_LENGTH) {
      name = name
        .replace("internal", "int")
        .replace("external", "ext")
        .replace("backend", "b")
        .replace("frontend", "f")
        .replace("east", "e")
        .replace("west", "w")
        .replace("north", "n")
        .replace("south", "s");
    }


    if (name.length() > MAX_LENGTH) {
      name = name.substring(0, MAX_LENGTH);
    }

    return name;
  }

}
