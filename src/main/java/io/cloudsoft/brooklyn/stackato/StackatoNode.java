package io.cloudsoft.brooklyn.stackato;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jclouds.domain.LoginCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.SoftwareProcessEntity;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.location.MachineLocation;
import brooklyn.location.MachineProvisioningLocation;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.basic.jclouds.JcloudsLocation;
import brooklyn.location.basic.jclouds.templates.PortableTemplateBuilder;
import brooklyn.util.MutableMap;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.task.BasicTask;
import brooklyn.util.task.Tasks;

import com.google.common.base.Throwables;

public class StackatoNode extends SoftwareProcessEntity implements StackatoConfigKeys {

    private static final Logger log = LoggerFactory.getLogger(StackatoNode.class);
    
    @SetFromFlag("roles")
    public static BasicConfigKey<List> STACKATO_NODE_ROLES = new BasicConfigKey<List>(List.class, "stackato.node.roles", "a list of strings to set as the cluster roles");

    public static BasicConfigKey<List> STACKATO_OPTIONS_FOR_BECOME = new BasicConfigKey<List>(List.class, "stackato.node.become.options", 
            "a list of arguments to pass to 'stackato-admin become'");

    @SetFromFlag("masterIp")
    public static BasicConfigKey<String> MASTER_IP_ADDRESS = new BasicConfigKey<String>(String.class, "stackato.master.hostname.override", 
            "optional, IP or hostname to use for master (auto-discovered if not specified)");

    public StackatoNode(Entity owner) { this(new MutableMap(), owner); }
    public StackatoNode(Map flags, Entity owner) { 
        super(flags, owner);
        
        StackatoDeployment stackatoCluster = getStackatoDeployment();
        String masterIp = getConfig(MASTER_IP_ADDRESS);
        addOptionForBecomeIfNotPresent("-m",
                masterIp!=null ? masterIp :
                DependentConfiguration.attributeWhenReady(stackatoCluster, StackatoDeployment.MASTER_INTERNAL_IP));
        addOptionForBecomeIfNotPresent("-e", new BasicTask(new Callable() { public Object call() { 
            return getApiEndpoint(); 
        } }));
    }
    
    protected StackatoDeployment getStackatoDeployment() {
        Entity stackatoCluster = getOwner();
        while (!(stackatoCluster instanceof StackatoDeployment)) {
            if (stackatoCluster==null) throw new IllegalStateException(""+this+" is not part of any stackato cluster");
            stackatoCluster = stackatoCluster.getOwner();
        }
        return (StackatoDeployment) stackatoCluster;
    }
    
    @Override
    public Class getDriverInterface() {
        return StackatoSshDriver.class;
    }

    @Override
    public StackatoSshDriver getDriver() {
        return (StackatoSshDriver) super.getDriver();
    }
    
    public void addOptionForBecome(String first, Object ...others) {
        List opts = getConfig(STACKATO_OPTIONS_FOR_BECOME);
        if (opts==null) opts = new ArrayList();
        else opts = new ArrayList(opts);
        opts.add(first);
        for (Object other: others) opts.add(other);
        setConfig(STACKATO_OPTIONS_FOR_BECOME, opts);
    }

    public void addOptionForBecomeIfNotPresent(String first, Object ...others) {
        List opts = getConfig(STACKATO_OPTIONS_FOR_BECOME);
        if (opts==null) opts = new ArrayList();
        else opts = new ArrayList(opts);
        if (opts.contains(first)) return;
        opts.add(first);
        for (Object other: others) opts.add(other);
        setConfig(STACKATO_OPTIONS_FOR_BECOME, opts);
    }

    public String getApiEndpoint() {
        return "api."+getConfig(STACKATO_CLUSTER_NAME)+"."+getConfig(STACKATO_CLUSTER_DOMAIN);
    }

    public void startInLocation(MachineProvisioningLocation location) {
        if (location instanceof JcloudsLocation) {
            String provider = ((JcloudsLocation)location).getProvider();
            Object endpoint = ((JcloudsLocation)location).getLocationProperty("endpoint");
            String locationId = ((JcloudsLocation)location).getJcloudsProviderLocationId();

            if ((provider+endpoint).toLowerCase().contains("hpcloud")) {
                startInHpCloud(location);
                return;
            }
            if (provider.toLowerCase().contains("aws-ec2")) {
                if (locationId==null || "us-east-1".equals(locationId)) {
                    startInAwsUsEast(location);
                    return;
                }
                log.warn("On AWS, Stackato image only available in us-east-1; cannot use pre-built image to deploy in "+locationId);
            }
        }
        throw new UnsupportedOperationException("Location "+location+" not supported for "+this);
    }
    
    @Override
    protected Collection<Integer> getRequiredOpenPorts() {
        // from stackato web site.  but arbitrary high-numbered ports are needed for internal comms
        // (eg to access app on dea from router; otherwise you see a vcap router error)
        return Arrays.asList(80, 443, 22, 4222, 9022);
    }
    
    public void startInHpCloud(final MachineProvisioningLocation location) {
        PortableTemplateBuilder template = (PortableTemplateBuilder) new PortableTemplateBuilder().
                imageNameMatches("ActiveState Stackato v1.2.6 .*").
                minRam( getConfig(MIN_RAM_MB) );
        final Map flags = MutableMap.builder().
                put("templateBuilder", template).
                // we use default for convenience because HP cloud otherwise gets security group explosion!
                // default should allow everything
                put("securityGroups", Arrays.asList("default") ).
                put("callerContext", ""+this).
                build();
        // username not set here, because we connect as root using key, then switch to using stackato

		setAttribute(PROVISIONING_LOCATION, location);
        SshMachineLocation machine;
        try {
            machine = Tasks.withBlockingDetails("Provisioning machine in "+location, new Callable<SshMachineLocation>() {
                public SshMachineLocation call() throws NoMachinesAvailableException {
                    return createInCloud(location, flags);
                }});
            if (machine == null) throw new NoMachinesAvailableException(location);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }

        // TODO Rewrite so don't rely on driver being created until in startInLocation?
        // Could get location.obtain() to do the customization perhaps?
        // And remove duplication from startInAwsUsEast?
        StackatoSshDriver tempdriver = (StackatoSshDriver) newDriver(machine);
        //initDriver(machine);
        onMachineReady();
        tempdriver.customizeImage();
        // switch to being this user
        machine.configure(MutableMap.of("username", "stackato"));
        
		if (log.isDebugEnabled())
		    log.debug("While starting {}, obtained new location instance {}", this, 
		            (machine instanceof SshMachineLocation ? 
		                    machine+", details "+((SshMachineLocation)machine).getUser()+":"+Entities.sanitize(((SshMachineLocation)machine).getConfig()) 
		                    : machine));
        
		startInLocation(machine);
    }

    public void startInAwsUsEast(final MachineProvisioningLocation location) {
        PortableTemplateBuilder template = (PortableTemplateBuilder) new PortableTemplateBuilder().
                imageId("us-east-1/ami-f806a291").  //or:  imageNameMatches(".*stackato-v1.2.6.*")   (but slower and less reliable)
                // see security group below
//                options( new TemplateOptions().inboundPorts( ArrayUtils.toPrimitive(getRequiredOpenPorts().toArray(new Integer[0]))) ).
                minRam( getConfig(MIN_RAM_MB) );
        final Map flags = MutableMap.builder().
                put("templateBuilder", template).
                put("callerContext", ""+this).
                // we require a security group called 'default' with everything open
                // (high-numbered ports needed for internal stackato comms; list above insufficient)
                put("securityGroups", Arrays.asList("universal") ).
                put("userData", "127.0.0.1").
                put("customCredentials", LoginCredentials.builder().user("stackato").password("stackato").build()).
                build();

        
		setAttribute(PROVISIONING_LOCATION, location);
        SshMachineLocation machine;
        try {
            machine = Tasks.withBlockingDetails("Provisioning machine in "+location, new Callable<SshMachineLocation>() {
                public SshMachineLocation call() throws NoMachinesAvailableException {
                    return createInCloud(location, flags);
                }});
            if (machine == null) throw new NoMachinesAvailableException(location);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }

        // TODO Rewrite so don't rely on driver being created until in startInLocation?
        // Could get location.obtain() to do the customization perhaps?
        StackatoSshDriver tempdriver = (StackatoSshDriver) newDriver(machine);
        onMachineReady();
        tempdriver.customizeImage();
        // this should already be set...
        machine.configure(MutableMap.of("username", "stackato"));
        // clear any password set, since it is no longer valid, and we've switched to key-based login
        machine.configure(MutableMap.of("password", null));
        
		if (log.isDebugEnabled())
		    log.debug("While starting {}, obtained new location instance {}", this, 
		            (machine instanceof SshMachineLocation ? 
		                    machine+", details "+((SshMachineLocation)machine).getUser()+":"+Entities.sanitize(((SshMachineLocation)machine).getConfig()) 
		                    : machine));
        
		startInLocation(machine);
    }
    
    // TODO merge commonalities in above two methods

    public SshMachineLocation createInCloud(MachineProvisioningLocation location, Map flags) {
        //begin standard java-vification of middle code in superclass mathod
        if (!flags.containsKey("inboundPorts")) {
            Collection<Integer> ports = getRequiredOpenPorts();
            if (ports!=null && !ports.isEmpty()) flags.put("inboundPorts", ports);
        }
        if (!(location instanceof LocalhostMachineProvisioningLocation))
            log.info("SoftwareProcessEntity {} obtaining a new location instance in {} with ports {}", 
                    new Object[] { this, location, flags.get("inboundPorts") });

        SshMachineLocation machine;
        try {
            machine = (SshMachineLocation) location.obtain(flags);
            if (machine == null) throw new NoMachinesAvailableException(location);
        } catch (NoMachinesAvailableException e) {
            throw Throwables.propagate(e);
        }
        if (!(location instanceof LocalhostMachineProvisioningLocation))
            log.info("SoftwareProcessEntity {} obtained a new location instance {}, now preparing process there", this, machine);
        //end   
        return machine;
    }
    
    public void onMachineReady() {
        //nothing, by default
    }
    
    public static String join(Iterable list, String separator) {
        if (list==null) return "";
        Iterator li = list.iterator();
        if (!li.hasNext()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(""+li.next());
        while (li.hasNext()) {
            sb.append(separator);
            sb.append(""+li.next());
        }
        return sb.toString();
    }
    
    /** entity assumes machines are correctly configured for login by 'stackato' at this point */
    public void startInLocation(SshMachineLocation machine) {
        super.startInLocation((MachineLocation)machine);
        setAttribute(SERVICE_UP, true);
        log.info("Started Stackato node "+this);
    }
    
    public void becomeDesiredStackatoRole() {
        List roles = getConfig(STACKATO_NODE_ROLES);
        if (roles==null || roles.isEmpty()) throw new IllegalStateException("role required for "+this);
        
        List optsUnresolved = getConfig(STACKATO_OPTIONS_FOR_BECOME);
        List opts = new ArrayList();
        try {
            if (optsUnresolved!=null) for (Object o: optsUnresolved)
                opts.add(Tasks.resolveValue(o, Object.class, getExecutionContext()));
        } catch (Exception e) {
            Throwables.propagate(e);
        }
        if (opts==null || opts.isEmpty()) throw new IllegalStateException("options required for "+this);
        
        log.info(""+this+" becoming "+join(roles, " ")+" "+join(opts, " "));
        int result = getDriver().execute(Arrays.asList(
                // root needed by python for some roles (controller)
                getDriver().getRefreshSudoCommand(),
                "stackato-admin become "+join(roles, " ")+" "+join(opts, " ")
            ), ""+this+" becoming "+roles);
        if (result!=0) throw new IllegalStateException("error "+this+" becoming "+join(roles, " ")+" "+join(opts, " ")+": result code "+result);
    }
    
    public void blockUntilReadyToLaunch() {
        // no op by default
    }
}
