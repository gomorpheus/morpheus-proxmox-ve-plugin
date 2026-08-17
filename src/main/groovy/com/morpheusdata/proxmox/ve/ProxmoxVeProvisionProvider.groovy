package com.morpheusdata.proxmox.ve

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.LogLevel
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.TaskResult
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.Cloud
import com.morpheusdata.proxmox.ve.util.ProxmoxSshUtil
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.proxmox.ve.util.ProxmoxMiscUtil
import groovy.util.logging.Slf4j

/**
 * @author Neil van Rensburg
 */

@Slf4j
class ProxmoxVeProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, WorkloadProvisionProvider, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet { //, ProvisionProvider.BlockDeviceNameFacet {
	public static final String PROVISION_PROVIDER_CODE = 'proxmox-provision-provider'

    // Validation error messages
    private static final String VALIDATION_MSG_NO_NODE = 'Please select a ProxMox node'
    private static final String VALIDATION_MSG_NO_IMAGE = 'Please select a virtual image'
    private static final String VALIDATION_MSG_NO_NETWORK = 'Please select a network'
    private static final String VALIDATION_MSG_INACTIVE_NODE = 'This ProxMox node is currently inactive. Please select an active node'
    private static final String VALIDATION_MSG_IMAGE_DATASTORE_NOT_ATTACHED = "Invalid instance config: Selected Virtual Image '%s' disk datastore '%s' is not attached to selected node '%s'."
    private static final String VALIDATION_MSG_DATASTORE_NOT_ATTACHED = "Invalid instance config: Selected datastore '%s' is not attached to selected node '%s'."
    private static final String VALIDATION_MSG_NETWORK_NOT_ATTACHED = "Invalid instance config: Selected network '%s' is not attached to selected node '%s'."

	protected MorpheusContext context
	protected ProxmoxVePlugin plugin

	public ProxmoxVeProvisionProvider(ProxmoxVePlugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	@Override
	Boolean canAddVolumes() {
		return true;
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean supportsAgent() {
		return true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	Boolean createDefaultInstanceType() {
		return false;
	}



	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
				true, // successful
				'', // no message
				null, // no errors
				new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {@link ProxmoxVeCloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(
			path: Assets.PROXMOX_LOGO_STACKED.path,
			darkPath: Assets.PROXMOX_LOGO_STACKED_INVERTED.path
		)
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []


		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.proxmox.noAgent',
				category: 'provisionType.proxmox-provision-provider',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'noAgentInstall',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldGroup: 'Advanced Options',
				displayOrder: 4,
				required: false,
				enabled: true,
				editable: true,
				global: false,
				placeHolder: null,
				helpBlock: 'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
				defaultValue: false,
				custom: false,
				fieldClass: null
		)

		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []

		nodeOptions << new OptionType(
				name: 'virtual image',
				category:'provisionType.proxmox.custom',
				code: 'proxmox-node-image',
				fieldContext: 'containerType',
				fieldName: 'virtualImage.id',
				fieldCode: 'gomorpheus.label.vmImage',
				fieldLabel: 'VM Image',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder:10,
				fieldClass:null,
				required: false,
				editable: false,
				noSelection: 'Select',
				optionSourceType: "proxmox",
				optionSource: 'proxmoxVirtualImages'
		)

		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		return this.getStorageVolumeTypes()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		return this.getStorageVolumeTypes()
	}

	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []

		volumeTypes << new StorageVolumeType(
				name: "Proxmox VM Generic Volume Type",
				code: "proxmox.vm.generic.volume.type",
				externalId: "proxmox.vm.generic.volume.type",
				displayOrder: 0,
				editable: true,
				resizable: true
		)

		return volumeTypes
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		//plans << new ServicePlan([code:'proxmox-ve-vm-512', name:'1 vCPU, 512MB Memory', description:'1 vCPU, 512MB Memory', sortOrder:0,
		//								 maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCores:1,
		//								 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-1024', name:'1 vCPU, 1GB Memory', description:'1 vCPU, 1GB Memory', sortOrder:1,
								  maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048', name:'1 vCPU, 2GB Memory', description:'1 vCPU, 2GB Memory', sortOrder:2,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-2048-2', name:'2 vCPU, 2GB Memory', description:'2 vCPU, 2GB Memory', sortOrder:2,
								  maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096', name:'1 vCPU, 4GB Memory', description:'1 vCPU, 4GB Memory', sortOrder:3,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:1,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-4096-24', name:'2 vCPU, 4GB Memory', description:'2 vCPU, 4GB Memory', sortOrder:3,
								  maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
								  maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-8192', name:'2 vCPU, 8GB Memory', description:'2 vCPU, 8GB Memory', sortOrder:4,
								  maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-16384', name:'2 vCPU, 16GB Memory', description:'2 vCPU, 16GB Memory', sortOrder:5,
								  maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCores:2,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-24576', name:'4 vCPU, 24GB Memory', description:'4 vCPU, 24GB Memory', sortOrder:6,
								  maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCores:4,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-vm-32768', name:'4 vCPU, 32GB Memory', description:'4 vCPU, 32GB Memory', sortOrder:7,
								  maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCores:4,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		plans << new ServicePlan([code:'proxmox-ve-internal-custom', editable:false, name:'Proxmox Custom', description:'Proxmox Custom', sortOrder:0,
								  customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
								  maxStorage:0l, maxMemory: 0l,  maxCpu:0])
		return plans
	}


	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
        log.debug("VALIDATION OPTS: $opts")

		def rtn = ServiceResponse.success()

        try {
            // Node, image and network fields are required
            def basicValidation = validateProvisioningOptions(opts)
            if(!basicValidation.success) {
                rtn.success = false
                rtn.errors = basicValidation.errors
                return rtn
            }

            HttpApiClient client = new HttpApiClient()
            Cloud cloud = context.async.cloud.get(opts.zoneId?.toLong()).blockingGet()
            if (!cloud) {
                rtn.success = false
                rtn.addError("zoneId", "Invalid Cloud/Zone ID")
                return rtn
            }

            ComputeServer selectedNode = getHypervisorHostByExternalId(cloud.id, opts.config?.proxmoxNode)

            if(selectedNode && selectedNode.powerState != ComputeServer.PowerState.on) {
                rtn.success = false
                rtn.addError("proxmoxNode", VALIDATION_MSG_INACTIVE_NODE)
                return rtn
            }

            Map authConfig = plugin.getAuthConfig(cloud)

            List<Map> wizardInterfaces = opts.networkInterfaces ?: []
            List<Map> instanceDisks = opts.volumes ?: []
            Long imageId = opts.config?.imageId ? (opts.config.imageId as Long) : null
            
            def nodeResp = ProxmoxApiComputeUtil.getProxmoxHypervisorHostByName(client, authConfig, opts.config?.proxmoxNode)
            Map proxmoxNode = nodeResp?.data

            if (!proxmoxNode) {
                log.error("Could not fetch Proxmox node details for node '${opts.config?.proxmoxNode}'")
                rtn.addError("proxmoxNode", "Unable to retrieve Proxmox node details for '${opts.config?.proxmoxNode}'")
                rtn.success = false
                return rtn
            }

            List<String> nodeDatastores = (proxmoxNode.datastores as List<String>) ?: []
            List<String> nodeNetworks = (proxmoxNode.networks as List<String>) ?: []

            //get proxmox datastores from API using morpheus datastore IDs from wizard
            List<String> wizardDatastoreExternalIds = []
            instanceDisks.each { Map vol ->
                if (vol.datastoreId != "auto" && vol.datastoreId != null) {
                    try {
                        def dsObj = context.async.cloud.datastore.listById([vol.datastoreId as Long]).blockingFirst(null)
                        if (dsObj?.externalId) {
                            wizardDatastoreExternalIds << dsObj.externalId
                        }
                    } catch (Exception e) {
                        log.warn("Failed to find datastore with id ${vol.datastoreId}: ${e.message}")
                    }
                }
            }
            
            List<Map> wizardDatastores = []
            if (wizardDatastoreExternalIds) {
                wizardDatastores = ProxmoxApiComputeUtil.getProxmoxDatastoresById(client, authConfig, wizardDatastoreExternalIds)?.data ?: []
            }

            //get virtualImage Datastores
            def virtualImage = imageId ? context.async.virtualImage.listById([imageId]).blockingFirst(null) : null
            Map proxmoxTemplate = null
            if (virtualImage?.externalId) {
                try {
                    def virtualImageExternalId = virtualImage.externalId as Long
                    proxmoxTemplate = ProxmoxApiComputeUtil.getTemplateById(client, authConfig, virtualImageExternalId)?.data
                } catch (Exception e) {
                    log.warn("Could not fetch template by externalId ${virtualImage.externalId}: ${e.message}")
                }
            }

            log.debug("PROXMOX TEMPLATE IS: $proxmoxTemplate")
            log.debug("SELECTED DATASTORES: $wizardDatastores")
            log.debug("SELECTED NODE DATASTORES: ${nodeDatastores}")
            log.debug("SELECTED NETWORKS: $wizardInterfaces")
            log.debug("SELECTED NODE NETWORKS: ${nodeNetworks}")

            //ensure that we aren't uploading the template for the first time
            if (proxmoxTemplate && proxmoxTemplate.datastores) {
                log.debug("SELECTED TEMPLATE DATASTORES: ${proxmoxTemplate.datastores}")

                //Check that the node can see see the template disk to copy it
                proxmoxTemplate.datastores.each { String templateDS ->
                    if (!nodeDatastores.contains(templateDS)) {
                        log.error("Error provisioning: Selected Virtual Image '${virtualImage.name}' disk datastore '$templateDS' is not attached to selected node '${opts.config.proxmoxNode}'.")
                        def errorMsg = String.format(VALIDATION_MSG_IMAGE_DATASTORE_NOT_ATTACHED, virtualImage.name, templateDS, opts.config.proxmoxNode)
                        rtn.addError("imageId", errorMsg)
                    } else {
                        log.debug("Datastore '$templateDS' is present and valid on proxmox node '${opts.config.proxmoxNode}'.")
                    }
                }

                if (rtn.errors && rtn.errors.size() > 0) {
                    rtn.success = false
                    return rtn
                }
            }

            //check that each disk datastore is present on the node
            wizardDatastores.each { Map wizardDS ->
                if (wizardDS.storage && !nodeDatastores.contains(wizardDS.storage)) {
                    log.error("Error provisioning: Selected datastore '$wizardDS.storage' is not attached to selected node '${opts.config.proxmoxNode}'.")
                    def errorMsg = String.format(VALIDATION_MSG_DATASTORE_NOT_ATTACHED, wizardDS.storage, opts.config.proxmoxNode)
                    rtn.addError("volume", errorMsg)
                } else {
                    log.debug("Datastore '$wizardDS.storage' is present and valid on proxmox node '${opts.config.proxmoxNode}'.")
                }

                if (rtn.errors && rtn.errors.size() > 0) {
                    rtn.success = false
                    return rtn
                }
            }

            //check that selected networks are attached to host
            wizardInterfaces.each { Map wizardNetwork ->
                def networkName = null
                
                // Check direct name or externalId in map
                if (wizardNetwork.network?.externalId) {
                    networkName = wizardNetwork.network.externalId
                } else if (wizardNetwork.network?.name && nodeNetworks.contains(wizardNetwork.network.name)) {
                    networkName = wizardNetwork.network.name
                }
                
                // If name not resolved, look up Network entity from Morpheus context by ID
                if (!networkName) {
                    def netIdRaw = wizardNetwork.network?.id ?: wizardNetwork.networkId ?: wizardNetwork.id
                    if (netIdRaw != null) {
                        try {
                            String netIdStr = netIdRaw.toString()
                            if (netIdStr.startsWith("network-")) {
                                netIdStr = netIdStr.replace("network-", "")
                            }
                            if (netIdStr.isNumber()) {
                                Long netId = netIdStr.toLong()
                                def networkObj = context.async.network.listById([netId]).blockingFirst(null)
                                if (networkObj) {
                                    networkName = networkObj.externalId ?: networkObj.name
                                }
                            } else if (netIdStr.startsWith("pool-")) {
                                Long poolId = netIdStr.replace("pool-", "").toLong()
                                def assocNetworks = context.async.network.list(new DataQuery().withFilter("pool.id", poolId)).blockingToList()
                                if (assocNetworks && assocNetworks.size() > 0) {
                                    networkName = assocNetworks[0].externalId ?: assocNetworks[0].name
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to resolve network object for ${netIdRaw}: ${e.message}")
                        }
                    }
                }

                // Fallback: if networkName is still null, but wizardNetwork.network.name is present, use it
                if (!networkName && wizardNetwork.network?.name) {
                    networkName = wizardNetwork.network.name
                }

                log.debug("Resolved network interface name for validation: ${networkName} (raw network map: ${wizardNetwork.network})")

                if (networkName) {
                    if (!nodeNetworks.contains(networkName)) {
                        log.error("Error provisioning: Selected network '${networkName}' is not attached to selected node '${opts.config.proxmoxNode}'. Node networks: ${nodeNetworks}")
                        def errorMsg = String.format(VALIDATION_MSG_NETWORK_NOT_ATTACHED, networkName, opts.config.proxmoxNode)
                        rtn.addError("networkInterface", errorMsg)
                    } else {
                        log.debug("Network '$networkName' is present and valid on proxmox node '${opts.config.proxmoxNode}'.")
                    }
                } else {
                    log.warn("Could not resolve specific Proxmox network interface name for network interface: ${wizardNetwork}. Skipping strict host-attachment check.")
                }
            }

            if (rtn.errors && rtn.errors.size() > 0) {
                rtn.success = false
            }

        } catch (Throwable e) {
            log.error("Unhandled error during validateWorkload: ${e.message}", e)
            rtn.success = false
            rtn.addError("validation", "Validation error: ${e.message}")
        }

        return rtn
	}

    private ServiceResponse validateProvisioningOptions(Map opts) {
        def rtn = ServiceResponse.success()

        if (!opts.config?.proxmoxNode) {
            rtn.addError("proxmoxNode", VALIDATION_MSG_NO_NODE)
        }

        if (!opts.config?.imageId) {
            rtn.addError("imageId", VALIDATION_MSG_NO_IMAGE)
        }

        if (opts.networkInterfaces?.size() > 0) {
            def hasNetwork = true
            opts.networkInterfaces?.each { Map netIface ->
                def net = netIface?.network
                def netId = net?.id ?: netIface?.networkId ?: netIface?.id
                def group = net?.group ?: netIface?.group
                def pool = net?.pool ?: netIface?.pool ?: netIface?.networkPoolId
                if (!group && !pool && netId == null) {
                    hasNetwork = false
                }
            }
            if (!hasNetwork) {
                rtn.addError("networkInterface", VALIDATION_MSG_NO_NETWORK)
            }
        } else {
            rtn.addError("networkInterface", VALIDATION_MSG_NO_NETWORK)
        }

        if (rtn.errors?.size() > 0) {
            rtn.success = false
        }

        return rtn
    }

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug("In runWorkload...")

		log.debug("WORKLOAD: \n $workload")
		log.debug("WORKLOADREQUEST: \n $workloadRequest")
		log.debug("WORKLOAD OPTS: \n $opts")
		log.debug("SKIP AGENT INSTALL: \n $opts.config.noAgentInstall")

		def skipAgent = false
		if (opts.config.noAgentInstall?.toString()?.toLowerCase() == "true") {
			skipAgent = true
			workloadRequest.cloudConfigUser = workloadRequest.cloudConfigUser
					.readLines()
					.findAll { !it.contains('api/server-script/agentInstall') }
					.join('\n')
		}

		log.debug("Cloud-Init User-Data User: $workloadRequest.cloudConfigUser")
		log.debug("Cloud-Init User-Data Network: $workloadRequest.cloudConfigNetwork")

		try {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			VirtualImage virtualImage = server.sourceImage
			Map authConfig = plugin.getAuthConfig(cloud)
			HttpApiClient client = new HttpApiClient()
			String nodeId = workload.server.getConfigProperty('proxmoxNode') ?: null

			List<String> targetNetworks = server.getInterfaces().collect { it.network.externalId }

			server.getInterfaces().each { ComputeServerInterface iface ->
				log.debug("IFACE NETWORK: $iface.network.externalId")
			}

			ComputeServer hvNode = getHypervisorHostByExternalId(cloud.id, nodeId)
			if (!hvNode.sshHost || !hvNode.sshUsername || !hvNode.sshPassword) {
				return new ServiceResponse<ProvisionResponse>(
						false,
						"SSH credentials required on host for provisioning to work. Edit the hypervisor host properties under the cloud Hosts tab.",
						null,
						new ProvisionResponse(
								success: false
						)
				)
			}

			DatastoreIdentity imgDS
			try {
				imgDS = context.cloud.datastore.getDefaultImageDatastoreForAccount(server.cloud.id, server.cloud.account.id).blockingGet()
			} catch(e) {
                log.debug("Unable to get Default Image Datastore")
				log.debug("Error: ${e}")
				log.debug("Getting general default datastore...")
				imgDS = getDefaultDatastore(cloud.id)
			}

			log.debug("IMAGE Datastore: $imgDS.name")
			String imageExternalId = ensureTemplateAvailable(client, authConfig, cloud, virtualImage, hvNode, imgDS.name)
			if (!imageExternalId) {
				return new ServiceResponse<ProvisionResponse>(
						false,
						"Unable to get Image Template ExternalId, or unable to create Template.",
						null,
						new ProvisionResponse(
								success: false
						)
				)
			}

			List<Map> existingCloneDisks = ProxmoxApiComputeUtil.getExistingVMStorage(client, authConfig, nodeId, imageExternalId)
			int nextScsi = ProxmoxApiComputeUtil.getHighestScsiDisk(existingCloneDisks) + 1
			String rootDiskLabel = existingCloneDisks.find { it.isRoot }?.label

			server.volumes.each {vol ->
				vol.deviceName = vol.rootVolume ? rootDiskLabel : "scsi$nextScsi"
				if (!vol.rootVolume) nextScsi++
				vol.externalId = vol.deviceName
				vol.deviceDisplayName = vol.deviceName
				if (!vol.datastore) {
					Datastore ds = getDefaultDatastore(cloud.id)
					vol.setDatastore((DatastoreIdentityProjection) ds)
				}
				context.services.storageVolume.save(vol)
			}
			server = saveAndGet(server)

			def ifCounter = 0
			server.interfaces.each { ComputeServerInterface iface ->
				iface.externalId = "net$ifCounter"
				context.services.computeServer.computeServerInterface.save(iface)
			}
			server = saveAndGet(server)

			server.computeServerType = context.async.cloud.findComputeServerTypeByCode("proxmox-qemu-vm").blockingGet()
			server.serverOs = server.serverOs ?: virtualImage?.osType
			server.osType = (server.serverOs?.platform == PlatformType.windows ? 'windows' : 'linux') ?: virtualImage?.platform
			server.parentServer = hvNode
			server.osDevice = '/dev/sda'
			server.lvmEnabled = false
			server.status = 'provisioned'
			server.serverType = 'vm'
			server.managed = true
			server.discovered = false
			if(server.osType == 'windows') {
				server.guestConsoleType = ComputeServer.GuestConsoleType.rdp
			} else if(server.osType == 'linux') {
				server.guestConsoleType = ComputeServer.GuestConsoleType.ssh
			}
			server.account = cloud.getAccount()
			server.cloud = cloud
			server = saveAndGet(server)

			log.debug("Provisioning/cloning: ${workload.getInstance().name} from Image Id: $imageExternalId on node: $nodeId")
			log.debug("Provisioning/cloning: ${workload.getInstance().name} with $server.coresPerSocket cores and $server.maxMemory memory")
			ServiceResponse rtnClone = ProxmoxApiComputeUtil.cloneTemplate(client, authConfig, imageExternalId, workload.getInstance().name, nodeId, server)

			if (!rtnClone.success) {
				def errorMessage = rtnClone.error ?: rtnClone.msg ?: 'Provisioning failed'
				log.error("Provisioning failed: ${errorMessage}")
				
				// Set the error message on the server for display in UI
				server.statusMessage = errorMessage
				server.status = 'failed'
				saveAndGet(server)
				
				return new ServiceResponse<ProvisionResponse>(
						false,
						errorMessage,
						[(server.id.toString()): errorMessage], // Pass error as map for UI display
						new ProvisionResponse(success: false)
				)
			}

			server.internalId = rtnClone.data.vmId
			server.externalId = rtnClone.data.vmId
			server = saveAndGet(server)

			def installAgentAfter = false
			if(virtualImage?.isCloudInit() && workloadRequest?.cloudConfigUser) {
				log.debug("Configuring Cloud-Init")
				// Get the actual storage used by the VM (important for cross-node clones)
				String actualStorage = ProxmoxApiComputeUtil.getVMActualStorage(client, authConfig, nodeId, rtnClone.data.vmId)
				if (!actualStorage) {
					log.warn("Could not determine actual storage for VM, falling back to requested storage")
					actualStorage = server.volumes.find {it.rootVolume }?.datastore?.externalId
				}
				log.debug("Using storage '$actualStorage' for Cloud-Init drive")
				ProxmoxSshUtil.createCloudInitDrive(context, hvNode, workloadRequest, rtnClone.data.vmId, actualStorage)
			} else {
				log.debug("Non Cloud-Init deployment...")
			}

			ProxmoxApiComputeUtil.startVM(client, authConfig, nodeId, rtnClone.data.vmId)

			return new ServiceResponse<ProvisionResponse>(
					true,
					"Provisioned",
					null,
					new ProvisionResponse(
							success: true,
							skipNetworkWait: false,
							installAgent: false,
							externalId: server.externalId,
							noAgent: skipAgent
					)
			)
		} catch(e) {
			log.error("Error during provisioning: ${e}")
			return new ServiceResponse<ProvisionResponse>(
					false,
					"Provisioning failed: ${e}",
					null,
					new ProvisionResponse(success: false)
			)
		}
	}


	protected buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, VirtualImage virtualImage, Map connection, Map opts) {
		log.debug("buildRunConfig: {}, {}, {}, {}", workload, workloadRequest, virtualImage, opts)
		Map workloadConfig = workload.getConfigMap()
		ComputeServer server = workload.server
		Cloud cloud = server.cloud

		def maxMemory = server.maxMemory

		//NETWORK
		//why would the network be missing on the primary interface?
		def network = workloadRequest.networkConfiguration.primaryInterface?.network
		if (!network && server.interfaces) {
			network = server.interfaces.find {it.primaryInterface}?.network
		}

		//DISK
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}
		List<StorageVolume> dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		def maxStorage
		if (rootVolume) {
			maxStorage = rootVolume.maxStorage
		} else {
			maxStorage = workloadConfig.maxStorage ?: server.plan.maxStorage
		}

		//TODO: adjust below OLVM lifted code for proxmox resource pools

		// get data center and cluster information
		//def zonePoolService = morpheus.async.cloud.pool
		//def datacenter
		//if (cloud.configMap.datacenter == 'all') {
		//	datacenter = zonePoolService.get(config.datacenterId.toLong()).blockingGet()
		//} else {
		//	datacenter = zonePoolService.find(
		//			new DataQuery().withFilter(new DataFilter('externalId', cloud.configMap.datacenter))
		//	).blockingGet()
		//}

		//def cluster = zonePoolService.get(config.clusterId.toLong()).blockingGet()



		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, workloadRequest.networkConfiguration, connection, workloadConfig, opts)

		runConfig += [
				serverId			: server.id,
				connection	 		: connection,
				securityRef			: workloadConfig.securityId,
				networkRef			: network?.externalId,
				//datacenterRef		: datacenter.externalId,
				//datacenterName	: datacenter.name,
				//clusterRef		: cluster.externalId,
				//clusterName		: cluster.name,
				server				: server,
				imageType			: virtualImage.imageType,
				serverOs			: server.serverOs ?: virtualImage.osType,
				osType				: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
				platform			: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
				osDiskName			: '/dev/sda1',
				dataDisks			: dataDisks,
				rootVolume			: rootVolume,
				virtualImage		: virtualImage,
				hostname			: server.getExternalHostname(),
				hosts				: server.getExternalHostname(),
				diskList			: [],
				domainName			: server.getExternalDomain(),
				serverInterfaces	: server.interfaces,
				fqdn				: server.getExternalHostname() + '.' + server.getExternalDomain(),

				name              	: server.name,
				instanceId		  	: workload.instance.id,
				containerId       	: workload.id,
				account 		  	: server.account,
				osDiskSize		  	: maxStorage.div(ComputeUtility.ONE_GIGABYTE),
				maxStorage        	: maxStorage,
				maxMemory		  	: maxMemory,
				applianceServerUrl	: workloadRequest.cloudConfigOpts?.applianceUrl,
				workloadConfig    	: workloadConfig,
				timezone          	: (server.getConfigProperty('timezone') ?: cloud.timezone),
				proxySettings     	: workloadRequest.proxyConfiguration,
				noAgent           	: (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
				installAgent      	: (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
				userConfig        	: workloadRequest.usersConfiguration,
				cloudConfig	      	: workloadRequest.cloudConfigUser,
				networkConfig	  	: workloadRequest.networkConfiguration
		] + opts

		//TODO
		//runConfig.virtualImageLocation = ensureVirtualImageLocation(connection, virtualImage, server.cloud)

		return runConfig
	}


	private runSshCmd(ComputeServer hvNode, String cmd) {
		TaskResult result = context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, cmd, "", "", "", false, LogLevel.info, true, null, false).blockingGet()
		if (!result.success) {
			def errorMsg = "SSH FAILED on ${hvNode.sshHost}: ${cmd} | Exit Code: ${result.exitCode} | Output: ${result.output} | Error: ${result.error}"
			log.error(errorMsg)
			throw new Exception(errorMsg)
		}
	}


	private Datastore getDefaultDatastore(Long cloudId, boolean imageStore = false) {
		log.debug("getDefaultDatastoreName()...")
		//returns the largest non-local datastore
		Datastore rtn = null
		//context.async.cloud.datastore.getDefaultImageDatastoreForAccount()
		context.async.cloud.datastore.list(new DataQuery().withFilters([
				new DataFilter("refType", "ComputeZone"),
				new DataFilter("refId", cloudId)
		])).blockingForEach { ds ->
			if (ds) {
				if (rtn == null) {
					rtn = ds
				} else if (ds.defaultStore) {
					return ds
				} else if (ds.getFreeSpace() > rtn.getFreeSpace()) {
					rtn = ds
				}
			}
		}
		return rtn
	}


	private ComputeServer getHypervisorHostByExternalId(Long cloudId, String externalId) {
		log.debug("Fetch Hypervisor Host by Cloud/External Id: $cloudId/$externalId")

		ComputeServer hvNode
		def hostIdentityProjection = context.async.computeServer.listIdentityProjections(cloudId, null).filter {
			ComputeServerIdentityProjection projection ->
				if (projection.externalId == externalId) {
					return true
				}
				false
		}.subscribe {
			log.debug("Found Host IdentityProjection: $it.id")
			List<Long> idList = [it.id]
			hvNode = context.async.computeServer.listById(idList).blockingFirst()
			log.debug("Returning hvHost: $hvNode.sshHost")
		}

		return hvNode
	}



	/**
	 * Ensure template is available on the target node
	 * Handles three scenarios:
	 * 1. New image + new host: upload image, create template, then clone
	 * 2. Old image + new host: create template on new host, then clone
	 * 3. Old image + old host: directly clone template
	 */
	private ensureTemplateAvailable(HttpApiClient client, Map authConfig, Cloud cloud, VirtualImage virtualImage, ComputeServer hvNode, String targetDS) {
		def imageExternalId
		def lock
		def lockKey = "proxmox.ve.imageupload.${cloud.regionCode}.${virtualImage?.id}.${hvNode.externalId}".toString()

		try {
			lock = context.acquireLock(lockKey, [timeout: 60l * 60l * 1000l, ttl: 60l * 60l * 1000l]).blockingGet()
			
			VirtualImageLocation existingLocation = null
			if (virtualImage?.externalId) {
				log.debug("Checking for existing template: $virtualImage.externalId on node: $hvNode.externalId")
				
				String templateNodeName = ProxmoxApiComputeUtil.findNodeForVM(client, authConfig, virtualImage.externalId)
				if (templateNodeName == hvNode.externalId) {
					// Scenario 3: Template already exists on target node
					log.debug("Template ${virtualImage.externalId} already exists on target node ${hvNode.externalId} - using existing template")
					return virtualImage.externalId
				} else if (templateNodeName) {
					log.debug("Template ${virtualImage.externalId} exists on node ${templateNodeName}, but not on target node ${hvNode.externalId}")
					log.debug("Will use cross-node cloning from ${templateNodeName} to ${hvNode.externalId}")
					return virtualImage.externalId
				}
			}
			
			def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
			String imageFile = cloudFiles?.find { cloudFile ->
				cloudFile.name.toLowerCase().endsWith(".qcow2") ||
					cloudFile.name.toLowerCase().endsWith(".img") ||
					cloudFile.name.toLowerCase().endsWith(".raw")
			}
			
			if (!imageFile) {
				// No image file in Morpheus - this is a synced template from Proxmox
				log.warn("No image file found in Morpheus storage for ${virtualImage.name}. This appears to be a synced Proxmox template.")
				log.warn("Template should exist on Proxmox cluster. If not found, please upload the image to Morpheus or create template on Proxmox manually.")
				throw new Exception("No valid image file found for virtual image ${virtualImage.name}. Please upload the image file to Morpheus.")
			}
			
			String fileName = new File(imageFile).getName()
			String remoteImagePath = "${ProxmoxSshUtil.REMOTE_IMAGE_DIR}/$fileName"
			
			def checkFileCmd = "test -f $remoteImagePath && echo 'EXISTS' || echo 'NOT_EXISTS'"
			def fileCheckResult = context.executeSshCommand(hvNode.sshHost, 22, hvNode.sshUsername, hvNode.sshPassword, checkFileCmd, "", "", "", false, LogLevel.info, true, null, false).blockingGet()
			if (!fileCheckResult.success) {
				log.error("SSH FAILED on ${hvNode.sshHost}: file check | Exit Code: ${fileCheckResult.exitCode} | Output: ${fileCheckResult.output} | Error: ${fileCheckResult.error}")
				throw new Exception("Failed to check if image exists on ${hvNode.sshHost}: ${fileCheckResult.error}")
			}
			boolean imageExistsOnNode = fileCheckResult.output?.contains('EXISTS')
			
			if (!imageExistsOnNode) {
				// Scenario 1: New image on new host - upload image and create template
				log.debug("Uploading image and creating template")
				remoteImagePath = ProxmoxSshUtil.uploadImage(context, hvNode, imageFile)
				imageExternalId = ProxmoxSshUtil.createTemplateFromImage(context, client, authConfig, virtualImage, hvNode, targetDS, remoteImagePath)
			} else {
				// Scenario 2: Old image on new host - create template from existing image file
				log.debug("Creating template from existing image file")
				imageExternalId = ProxmoxSshUtil.createTemplateFromImage(context, client, authConfig, virtualImage, hvNode, targetDS, remoteImagePath)
			}
			
			if (!virtualImage.externalId) {
				virtualImage.externalId = imageExternalId
				log.debug("Updating virtual image $virtualImage.name with external ID $imageExternalId")
				context.async.virtualImage.bulkSave([virtualImage]).blockingGet()
			}
			
			VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
				virtualImage: virtualImage,
				externalId  : imageExternalId,
				imageRegion : cloud.regionCode,
				code        : "proxmox.ve.image.${cloud.id}.${hvNode.externalId}.$imageExternalId",
				internalId  : imageExternalId,
				refId		: cloud.id,
				refType		: 'ComputeZone',
			])
			context.async.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()
			log.debug("Created VirtualImageLocation for template $imageExternalId on node ${hvNode.externalId}")

		} finally {
			context.releaseLock(lockKey, [lock:lock]).blockingGet()
		}
		return imageExternalId
	}


	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		log.debug("Finalizing proxmox VM: $workload.server.externalId")

		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		try {
			HttpApiClient client = new HttpApiClient()
			ComputeServer computeServer = workload.server
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.stopVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing stop on VM: ${e}", e
			return ServiceResponse.error("Error performing stop on VM: ${e}")
		}
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		try {
			HttpApiClient client = new HttpApiClient()
			ComputeServer computeServer = workload.server
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.startVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing start on VM: ${e}", e
			return ServiceResponse.error("Error performing start on VM: ${e}")
		}
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		def stopResult = stopWorkload(workload)
		if (!stopResult.success) {
			log.error("Failed to stop workload ${workload.id} during restart: ${stopResult.msg}")
			return ServiceResponse.error("Error restarting workload: Failed to stop - ${stopResult.msg}")
		}
		def startResult = startWorkload(workload)
		if (!startResult.success) {
			log.error("Failed to start workload ${workload.id} during restart: ${startResult.msg}")
			return ServiceResponse.error("Error restarting workload: Failed to start - ${startResult.msg}")
		}
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		try {
			HttpApiClient deleteClient = new HttpApiClient()
			HttpApiClient stopClient = new HttpApiClient()
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			Map authConfig = plugin.getAuthConfig(cloud)

			ProxmoxApiComputeUtil.stopVM(stopClient, authConfig, server.parentServer.name, server.externalId)
			sleep(5000)
			return ProxmoxApiComputeUtil.destroyVM(deleteClient, authConfig, server.parentServer.name, server.externalId)
		} catch (e) {
			log.error "Error performing destroy on VM: ${e}", e
			return ServiceResponse.error("Error performing destroy on VM: ${e}")
		}
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		try {
			HttpApiClient client = new HttpApiClient()
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.stopVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing stop on VM: ${e}", e
			return ServiceResponse.error("Error performing stop on VM: ${e}")
		}
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		try {
			HttpApiClient client = new HttpApiClient()
			Map authConfig = plugin.getAuthConfig(computeServer.cloud)

			return ProxmoxApiComputeUtil.startVM(client, authConfig, computeServer.parentServer.name, computeServer.externalId)
		} catch (e) {
			log.error "Error performing start on VM: ${e}", e
			return ServiceResponse.error("Error performing start on VM: ${e}")
		}
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'Proxmox VE Provisioning'
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = context.async.computeServer.bulkSave([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return context.async.computeServer.get(server.id).blockingGet()
	}


	////Gotcha, if no logo add the below
	@Override
	HostType getHostType() {
		HostType.vm
	}


	// ResizeFacet
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug("resizeWorkload")

		return resizeServer(workload.server, resizeRequest, opts)
	}


	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		log.debug("resizeServer")

		HttpApiClient resizeClient = new HttpApiClient()
		List<ServiceResponse> responses = [
			resizeWorkloadComputePlan(server, resizeRequest, opts, resizeClient),
			resizeWorkloadDisks(server, resizeRequest, opts, resizeClient),
			resizeWorkloadNetworks(server, resizeRequest, opts, resizeClient)
		]

		def rtn = ServiceResponse.success()
		responses.findAll {!it.success }.each {
			rtn.success = false
			rtn.msg = (rtn.msg ?: '') + (it.msg ?: it.error ?: 'Unknown error') + '\n'
		}

		return rtn
	}



	ServiceResponse resizeWorkloadComputePlan(ComputeServer computeServer, ResizeRequest resizeRequest, Map opts, HttpApiClient resizeClient) {
		boolean isWorkload = true
		ServiceResponse rtn = ServiceResponse.success()
		def authConfigMap = plugin.getAuthConfig(computeServer.cloud)

		try {
			//Compute
			computeServer.status = 'resizing'
			computeServer = saveAndGet(computeServer)

			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores

			def currentMemory
			def currentCores

			if (isWorkload) {
				currentMemory = computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong()
				currentCores = computeServer.maxCores ?: 1
			} else {
				currentMemory = computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong()
				currentCores = computeServer.maxCores ?: 1
			}
		def neededMemory = requestedMemory - currentMemory
		def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)
		def allocationSpecs = [externalId: computeServer.externalId, maxMemory: requestedMemory, maxCpu: requestedCores]
		if (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0) {
			log.debug("Resizing VM with specs: ${allocationSpecs}")
			log.debug("Resizing vm: ${computeServer.name} with $computeServer.coresPerSocket cores and $computeServer.maxMemory memory")

			def resizeResult = ProxmoxApiComputeUtil.resizeVM(resizeClient, authConfigMap, computeServer.parentServer.name, computeServer.externalId, requestedCores, requestedMemory, computeServer.volumes?.toList() ?: [], computeServer.interfaces?.toList() ?: [])

			if (!resizeResult.success) {
					log.error("Resize API call failed: ${resizeResult.msg}")
					computeServer.status = 'provisioned'
					computeServer.statusMessage = "Resize failed: ${resizeResult.msg}"
					saveAndGet(computeServer)
					return new ServiceResponse(success: false, msg: "Resize failed: ${resizeResult.msg}")
				}

				computeServer.maxMemory = requestedMemory
				computeServer.maxCores = requestedCores
				computeServer.coresPerSocket = 1
			} else {
				log.info("No resize needed - changes too small (neededMemory: ${neededMemory}, neededCores: ${neededCores})")
			}

			computeServer.status = 'provisioned'
			computeServer.statusMessage = null
			computeServer = saveAndGet(computeServer)
		} catch (e) {
			log.error("Exception during resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			computeServer.statusMessage = "Unable to resize server: ${e.message}"
			saveAndGet(computeServer)
			return new ServiceResponse(success: false, msg: "Unable to resize server: ${e.message}")
		}
		return new ServiceResponse(success: true, msg: "Server resized successfully")
	}



	ServiceResponse resizeWorkloadDisks(ComputeServer server, ResizeRequest resizeRequest, Map opts, HttpApiClient resizeClient) {
		def cloud = server.cloud
		def extNodeId = server.parentServer.externalId
		def extServerId = server.externalId
		Map authConfig = plugin.getAuthConfig(cloud)
		List<ServiceResponse> responses = []

		log.debug("Reconfigure: Resize Volumes to delete: $resizeRequest.volumesDelete")
		log.debug("Reconfigure: Resize Volumes to add: $resizeRequest.volumesAdd")
		log.debug("Reconfigure: Resize Volumes to update: $resizeRequest.volumesUpdate")

		//delete
		if (resizeRequest.volumesDelete) {
			List deleteVolsExtIds = resizeRequest.volumesDelete.collect { it.deviceName }
			List<StorageVolumeIdentityProjection> volumesDeleteProjections = resizeRequest.volumesDelete.collect { (StorageVolumeIdentityProjection) it }
			responses << ProxmoxApiComputeUtil.deleteVolumes(resizeClient, authConfig, extNodeId, extServerId, deleteVolsExtIds)
			context.async.storageVolume.remove(volumesDeleteProjections, server, true).blockingGet()
		}

		//add
		if (resizeRequest.volumesAdd) {
			List newVolumes = []
			List<Map> existingVMDisks = ProxmoxApiComputeUtil.getExistingVMStorage(resizeClient, authConfig, extNodeId, extServerId)
			int nextScsi = ProxmoxApiComputeUtil.getHighestScsiDisk(existingVMDisks)
			resizeRequest.volumesAdd.each { Map newVMDisk ->
				nextScsi++
				def newDiskConf = [
						account          : cloud.account,
						cloudId          : cloud.id,
						deviceName       : "scsi$nextScsi",
						deviceDisplayName: "scsi$nextScsi",
						externalId       : "scsi$nextScsi",
						maxStorage       : newVMDisk.maxStorage,
						refType          : "ComputeServer",
						refId            : server.id,
						name             : newVMDisk.name
				]
				if (newVMDisk.datastoreId == "auto") {
					newDiskConf.datastore = getDefaultDatastore(cloud.id)
				} else {
					newDiskConf.datastore = context.services.cloud.datastore.get(newVMDisk.datastoreId as Long)
				}

				StorageVolume newVol = new StorageVolume(newDiskConf)
				newVol.type = new StorageVolumeType(id: newVMDisk.storageType.toLong())
				newVolumes << newVol
			}
			log.debug("${newVolumes.size()} volumes to create")
			context.async.storageVolume.create(newVolumes, server).blockingGet()
			responses << ProxmoxApiComputeUtil.addVMDisks(resizeClient, authConfig, newVolumes, extNodeId, extServerId)
		}

		//update existing disks
		resizeRequest.volumesUpdate?.each { UpdateModel<StorageVolume> volumeUpdate ->
			StorageVolume existing = volumeUpdate.existingModel
			Map updateProps = volumeUpdate.updateProps
			if (updateProps.maxStorage > existing.maxStorage) {
				log.debug("resizing vm storage: {}", volumeUpdate)
				existing.maxStorage = updateProps.maxStorage as Long
				context.services.storageVolume.save(existing)
				responses << ProxmoxApiComputeUtil.resizeVMDisk(resizeClient, authConfig, existing, extNodeId, extServerId)
			}
		}

		if (responses.any{!it.success }) {
			def errorMessages = responses.findAll{!it.success}.collect{ it.msg ?: it.error ?: 'Disk operation failed' }.join("; ")
			return new ServiceResponse(success: false, msg: errorMessages)
		}
		return new ServiceResponse(success: true, msg: "VM Disk Volumes Updated")
	}



	ServiceResponse resizeWorkloadNetworks(ComputeServer server, ResizeRequest resizeRequest, Map opts, HttpApiClient resizeClient) {
		def cloud = server.cloud
		def extNodeId = server.parentServer.externalId
		def extServerId = server.externalId

		def authConfigMap = plugin.getAuthConfig(server.cloud)
		List<ServiceResponse> responses = []

		log.debug("Networks to Add: $resizeRequest.interfacesAdd")
		log.debug("Networks to Delete: $resizeRequest.interfacesDelete")
		log.debug("Networks to Update: $resizeRequest.interfacesUpdate")

		//delete
		if (resizeRequest.interfacesDelete) {
			//causes database constraint errors
			//context.services.computeServer.computeServerInterface.bulkRemove(resizeRequest.interfacesDelete)
			context.async.computeServer.computeServerInterface.remove(resizeRequest.interfacesDelete, server).blockingGet()
			responses << ProxmoxApiComputeUtil.removeNetworkInterfaces(resizeClient, authConfigMap, resizeRequest.interfacesDelete, extNodeId, extServerId)
		}

		//add
		List<Map> proxVMInterfaces = ProxmoxApiComputeUtil.getExistingVMInterfaces(resizeClient, authConfigMap, extNodeId, extServerId)
		List<ComputeServerInterface> newInterfaces = []
		if (resizeRequest.interfacesAdd.each) {
			int nicCounter = proxVMInterfaces.size()
			resizeRequest.interfacesAdd.each { Map nic ->
				log.debug("NIC map: $nic")
				def networkObj = context.services.network.get(nic.network.id)
				Map newInterfaceProps = [
						externalId		: "net$nicCounter",
						name			: "net$nicCounter",
						network   		: networkObj,
						dhcp			: nic.network?.dhcpServer,
						primaryInterface: false
				]
				if (nic.ipAddress) {
					newInterfaceProps["ipAddress"] = nic.ipAddress
				}
				nicCounter++
				ComputeServerInterface newInterface = new ComputeServerInterface(newInterfaceProps)
				newInterfaces << newInterface
			}
			responses << ProxmoxApiComputeUtil.addVMNics(resizeClient, authConfigMap, newInterfaces, extNodeId, extServerId)
			context.async.computeServer.computeServerInterface.create(newInterfaces, server).blockingGet()
		}

		if (responses.any{!it.success }) {
			def errorMessages = responses.findAll{!it.success}.collect{ it.msg ?: it.error ?: 'Network operation failed' }.join("; ")
			return new ServiceResponse(success: false, msg: errorMessages)
		}
		return new ServiceResponse(success: true, msg: "VM Network Interfaces Updated")
	}


	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.debug("validateHost")
		return null
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("prepareHost")
		return null
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost")
		return null
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		log.debug("finalizeHost")
		return null
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean computeZonePoolRequired() {
		return false
	}
}
