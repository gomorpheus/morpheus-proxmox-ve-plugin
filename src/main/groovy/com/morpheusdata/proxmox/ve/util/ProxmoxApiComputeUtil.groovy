package com.morpheusdata.proxmox.ve.util

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import org.apache.http.entity.ContentType
import groovy.json.JsonSlurper

/**
 * @author Neil van Rensburg
 */

@Slf4j
class ProxmoxApiComputeUtil {

    //static final String API_BASE_PATH = "/api2/json"
    static final Long API_CHECK_WAIT_INTERVAL = 2000


    static addVMNics(HttpApiClient client, Map authConfig, List<ComputeServerInterface> newNics, String node, String vmId) {
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def diskAddOpts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [:],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            newNics.each { nic ->
                diskAddOpts.body["$nic.externalId"] = "bridge=$nic.network.externalId,model=e1000e"
            }

            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(diskAddOpts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Adding NICs: ${e}", e
            return ServiceResponse.error("Error Adding NICs: ${e}")
        }
    }


    static List<Map> getExistingVMInterfaces(HttpApiClient client, Map authConfig, String nodeId, String vmId) {

        def vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig).data
        log.debug("VM Config Info: $vmConfigInfo")
        def nicInterfaces = vmConfigInfo.findAll { k, v -> k ==~ /net\d+/ }.collect { k, v -> [ label: k, value: v] }
        return nicInterfaces
    }


    static removeNetworkInterfaces(HttpApiClient client, Map authConfig, List<ComputeServerInterface> deletedNics, String node, String vmId) {
        log.debug("deleteVolumes")
        def tokenCfg = getApiV2Token(authConfig).data
        def nicRemoveOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
        ]

        try {
            def success = true
            def errorMsg = ""
            deletedNics.each { ComputeServerInterface nic ->
                nicRemoveOpts.body.delete = nic.externalId
                def nicRemoveResults = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(nicRemoveOpts),
                        'PUT'
                )
                if (!nicRemoveResults.success) {
                    errorMsg += "$nicRemoveResults.error\n"
                    success = false
                }
            }
            return new ServiceResponse(success: success, msg: errorMsg)
        } catch (e) {
            log.error "Error removing VM Network Interface: ${e}", e
            return ServiceResponse.error("Error removing VM Network Interface: ${e}")
        }
    }


    static resizeVMDisk(HttpApiClient client, Map authConfig, StorageVolume updatedVolume, String node, String vmId) {
        def tokenCfg = getApiV2Token(authConfig).data
        def diskResizeOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        disk: "$updatedVolume.deviceName",
                        size: "${updatedVolume.maxStorage as Long / 1024 / 1024 / 1024}G"
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
        ]

        def results = client.callJsonApi(
                (String) authConfig.apiUrl,
                "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/resize",
                null, null,
                new HttpApiClient.RequestOptions(diskResizeOpts),
                'PUT'
        )

        return results
    }


    static addVMDisks(HttpApiClient client, Map authConfig, List<StorageVolume> newVolumes, String node, String vmId) {
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def diskAddOpts = [
                headers  : [
                    'Content-Type'       : 'application/json',
                    'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                    'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                    delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
            ]

            newVolumes.each { vol ->
                def size = "${vol.maxStorage as Long / 1024 / 1024 / 1024}"
                diskAddOpts.body["$vol.deviceName"] = "${vol.datastore.externalId}:$size,size=${size}G"
            }

            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                    null, null,
                    new HttpApiClient.RequestOptions(diskAddOpts),
                    'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }


    static deleteVolumes(HttpApiClient client, Map authConfig, String node, String vmId, List<String> ids) {
        log.debug("deleteVolumes")
        def tokenCfg = getApiV2Token(authConfig).data
        def diskRemoveOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        delete: ""
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
        ]

        try {
            def success = true
            def errorMsg = ""
            ids.each { String diskId ->
                diskRemoveOpts.body.delete = diskId
                log.debug("Delete request path: \n${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config")
                log.debug("Delete request body: \n$diskRemoveOpts")
                def diskRemoveResults = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(diskRemoveOpts),
                        'PUT'
                )
                if (!diskRemoveResults.success) {
                    errorMsg += "$diskRemoveResults.error\n"
                    success = false
                }
            }
            return new ServiceResponse(success: success, msg: errorMsg)
        } catch (e) {
            log.error "Error removing VM disk: ${e}", e
            return ServiceResponse.error("Error removing VM disk: ${e}")
        }
    }


    static int getHighestScsiDisk(diskList) {
        def scsiDisks = diskList.findAll { it.label ==~ /scsi\d+/ }
        if (!scsiDisks) return -1

        def highest = scsiDisks.max { it.label.replace("scsi", "").toInteger() }
        return highest.label.replace("scsi", "").toInteger()
    }



    static resizeVM(HttpApiClient client, Map authConfig, String node, String vmId, Long cpu, Long ram, List<StorageVolume> volumes, List<ComputeServerInterface> nics) {
        log.debug("resizeVMCompute")
        Long ramValue = ram / 1024 / 1024

        def rootVolume = volumes.find {it.rootVolume }

        try {
            log.debug("Resize Boot Disk...")
            def initialTemlpateDisks = getExistingVMStorage(client, authConfig, node, vmId)
            def tokenCfg = getApiV2Token(authConfig).data
            def resizeOpts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        disk  : "${initialTemlpateDisks[0].label}",
                        size  : "${rootVolume.maxStorage as Long / 1024 / 1024 / 1024}G",
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
            ]

            def resizeResults = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/resize",
                    null, null,
                    new HttpApiClient.RequestOptions(resizeOpts),
                    'PUT'
            )

            log.debug("Post deployment Resize results: $resizeResults")
            log.debug("Resize compute, add additional Disks...")
            def opts = [
                headers  : [
                        'Content-Type'       : 'application/json',
                        'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                ],
                body     : [
                        node  : node,
                        vcpus : cpu,
                        cores : cpu,
                        memory: ramValue
                ],
                contentType: ContentType.APPLICATION_JSON,
                ignoreSSL: true
            ]

            volumes.each { vol ->
                if (!vol.rootVolume) {
                    def size = "${vol.maxStorage as Long / 1024 / 1024 / 1024}"
                    opts.body["$vol.deviceName"] = "${vol.datastore.externalId}:$size,size=${size}G"
                }
            }

//            def counter = 0
//            targetNetworks.each {network ->
//                opts.body["net$counter"] = "bridge=$network,model=e1000e"
//                counter++
//            }

            nics.each { nic ->
                opts.body["$nic.externalId"] = "bridge=$nic.network.externalId,model=e1000e"
            }

            log.debug("Setting VM Compute Size $vmId on node $node...")
            def results = client.callJsonApi(
                (String) authConfig.apiUrl,
                "${authConfig.v2basePath}/nodes/$node/qemu/$vmId/config",
                null, null,
                new HttpApiClient.RequestOptions(opts),
                'POST'
            )

            return results
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
    }



    static cloneTemplate(HttpApiClient client, Map authConfig, String templateId, String name, String nodeId, ComputeServer server) {
        log.debug("cloneTemplate: $templateId")
        Long vcpus = server.maxCores
        Long ram = server.maxMemory
        List<StorageVolume> volumes = server.volumes
        List<ComputeServerInterface> nics = server.interfaces
        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")
        StorageVolume rootVolume = volumes.find { it.rootVolume }
        
        // Find the actual node where the template resides (multi-node cluster support)
        String templateNode = findNodeForVM(client, authConfig, templateId)
        if (!templateNode) {
            log.error("Cannot find template $templateId on any node in the cluster")
            return ServiceResponse.error("Template $templateId not found on any node in the cluster")
        }
        
        if (templateNode != nodeId) {
            log.debug("Template $templateId is on node $templateNode, but cloning to node $nodeId (cross-node clone)")
        }
        
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            
            // Build request body
            def requestBody = [
                    newid: nextId,
                    target: nodeId,  // Target node for the new VM
                    vmid: templateId,
                    name: name,
                    full: true
            ]
            
            // For cross-node clones, check if storage exists on source node
            // If storage is specified and this is a cross-node clone, we need to be careful
            String requestedStorage = rootVolume?.datastore?.externalId
            if (requestedStorage) {
                if (templateNode == nodeId) {
                    // Same node clone - use specified storage
                    requestBody.storage = requestedStorage
                    log.debug("Same-node clone: using storage '$requestedStorage'")
                } else {
                    // Cross-node clone - only specify storage if it's shared storage
                    def sourceNodeStorages = getNodeStorages(client, authConfig, templateNode)
                    def targetNodeStorages = getNodeStorages(client, authConfig, nodeId)
                    
                    boolean isSharedStorage = sourceNodeStorages?.contains(requestedStorage) && 
                                            targetNodeStorages?.contains(requestedStorage)
                    
                    if (isSharedStorage) {
                        requestBody.storage = requestedStorage
                        log.debug("Cross-node clone: using shared storage '$requestedStorage'")
                    } else {
                        // Non-shared storage detected - omit storage parameter
                        // Proxmox will use default storage on target node
                        log.warn("Cross-node clone: storage '$requestedStorage' not shared between nodes, omitting storage parameter")
                    }
                }
            }
            
            log.debug("Selected Resource Pool Name: ${server?.resourcePool?.name}")
            if (server?.resourcePool?.name) requestBody.pool = server.resourcePool.name
            
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: requestBody,
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Cloning template $templateId from node $templateNode to VM $name($nextId) on node $nodeId")
            
            // Clone from the node where template actually resides
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$templateNode/qemu/$templateId/clone",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            if(!results?.success || results?.hasErrors()) {
                def errorMsg = "Clone operation failed"
                try {
                    def errorData = results.data
                    if (errorData?.message) {
                        errorMsg = errorData.message.trim()
                    } else if (errorData?.data?.message) {
                        errorMsg = errorData.data.message.trim()
                    } else if (results?.data) {
                        errorMsg = results.data?.toString()
                    }
                } catch (e) {
                    log.warn("Failed to parse error response: ${e.message}")
                    if (results?.data) {
                        errorMsg = results.data?.toString()
                    }
                }
                return ServiceResponse.error(errorMsg)
            }

            def resultData = null
            try {
                resultData = results.data
            } catch (e) {
                log.error("Failed to parse clone response: ${e.message}")
                return ServiceResponse.error("Failed to parse clone API response")
            }
            
            if (resultData?.errors) {
                log.error("Clone operation returned errors: ${resultData.errors}")
                return ServiceResponse.error("Clone failed: ${resultData.errors}")
            }

            rtn.success = true
            rtn.data = [vmId: nextId, apiResult: resultData]

            // Proxmox returns a task UPID that we should monitor
            def taskUPID = resultData?.data
            if (taskUPID) {
                log.debug("Clone task started with UPID: $taskUPID on node $templateNode")
                // Wait for the Proxmox task to complete on the source node
                ServiceResponse taskWaitResult = waitForTaskComplete(new HttpApiClient(), authConfig, templateNode, taskUPID, 3600L)
                
                if (!taskWaitResult?.success) {
                    log.error("Clone task failed for VM $nextId: ${taskWaitResult.msg}")
                    return ServiceResponse.error("Error Provisioning VM. Clone task error: ${taskWaitResult.msg}")
                }
                log.debug("Clone task completed successfully for VM $nextId")
            } else {
                log.warn("No task UPID returned from clone API, falling back to VM config check")
                // Fallback to old method
                ServiceResponse cloneWaitResult = waitForCloneToComplete(new HttpApiClient(), authConfig, templateId, nextId, nodeId, 3600L)
                
                if (!cloneWaitResult?.success) {
                    log.error("Clone wait failed for VM $nextId: ${cloneWaitResult.msg}")
                    return ServiceResponse.error("Error Provisioning VM. Wait for clone error: ${cloneWaitResult.msg}")
                }
            }

            log.debug("Resizing newly cloned VM. Spec: CPU: $vcpus,\n RAM: $ram,\n Volumes: $volumes,\n NICs: $nics")
            ServiceResponse rtnResize = resizeVM(new HttpApiClient(), authConfig, nodeId, nextId, vcpus, ram, volumes, nics)

            if (!rtnResize?.success) {
                log.error("Resize failed for VM $nextId: ${rtnResize.msg}")
                return ServiceResponse.error("Error Sizing VM Compute. Resize compute error: ${rtnResize.msg}")
            }

            log.debug("Successfully cloned and configured VM $nextId on node $nodeId")
        } catch(e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }




    static List<Map> getExistingVMStorage(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        // First try the provided node
        def vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig)
        
        // If VM/template not found on the provided node, search across cluster
        if (!vmConfigInfo.success || vmConfigInfo.hasErrors()) {
            log.warn("VM/template $vmId not found on node $nodeId, searching other nodes in cluster...")
            String actualNode = findNodeForVM(client, authConfig, vmId)
            if (actualNode && actualNode != nodeId) {
                log.debug("Found VM/template $vmId on node $actualNode instead of $nodeId")
                vmConfigInfo = callListApiV2(client, "nodes/$actualNode/qemu/$vmId/config", authConfig)
            }
        }
        
        if (!vmConfigInfo.success || !vmConfigInfo.data) {
            log.error("Failed to retrieve VM/template $vmId configuration from any node")
            return []
        }
        
        def vmConfigData = vmConfigInfo.data
        def validBootDisks = ["scsi0", "virtio0", "sata0", "ide0"]
        def bootEntries = vmConfigData.boot?.trim()?.replaceAll("order=", "")?.split(/[;,\s]+/)
        def bootDisk = ""

        def extractDiskKeys  = { config ->
            def diskPrefixes = ["scsi", "virtio", "sata", "ide"]
            def diskKeys = config.keySet().findAll { key ->
                diskPrefixes.any { prefix -> key ==~ /${prefix}\d+/ }
            }
            return diskKeys
        }
        List vmStorageList = []

        def vmDiskKeys = extractDiskKeys(vmConfigData)

        //Boot disk specified in config boot order
        bootEntries.each { String diskLabel ->
            if (!bootDisk && validBootDisks.contains(diskLabel)) {
                bootDisk = diskLabel
            }
        }

        if (!bootDisk) {
            //select boot disk based on proxmox disk names
            validBootDisks.each { String diskLabel ->
                if (!bootDisk && vmDiskKeys.contains(diskLabel)) {
                    bootDisk = diskLabel
                }
            }
        }

        if (!bootDisk) {
            throw new Exception("Boot disk for VM not found!")
        }

        vmStorageList << [ label: "$bootDisk", config: vmConfigData[bootDisk], isRoot: true ]
        vmDiskKeys.each { String diskLabel ->
            if (diskLabel != bootDisk) {
                vmStorageList << [ label: "$diskLabel", config: vmConfigData[diskLabel], isRoot: false ]
            }
        }

        return vmStorageList
    }
    /**
     * Find which node a VM or template resides on in a multi-node cluster
     * 
     * Uses /cluster/resources?type=vm endpoint to fetch only QEMU VMs/templates,
     * then filters client-side by vmid. Falls back to all resources if type parameter fails,
     * and ultimately checks each node individually if cluster query fails.
     * 
     * Proxmox API limitation: There is NO endpoint like /api2/json/qemu/{vmid}
     * All VM queries require: /api2/json/nodes/{node}/qemu/{vmid}
     * 
     * @param client HttpApiClient instance
     * @param authConfig Authentication configuration
     * @param vmId The VM or template ID to locate
     * @return The node name where the VM/template resides, or null if not found
     */
    static String findNodeForVM(HttpApiClient client, Map authConfig, String vmId) {
        try {
            // Try with type=vm filter first for better performance
            def qemuResources = callListApiV2(client, "cluster/resources?type=vm", authConfig)
            
            // If type parameter not supported, fall back to unfiltered query
            if (!qemuResources?.success || qemuResources?.hasErrors()) {
                log.debug("cluster/resources?type=vm not supported, trying without filter")
                qemuResources = callListApiV2(client, "cluster/resources", authConfig)
            }
            
            if (qemuResources?.success && qemuResources?.data) {
                // Filter for the specific VM/template (client-side filtering)
                def vmResource = qemuResources.data.find { 
                    it.type == "qemu" && it.vmid?.toString() == vmId?.toString()
                }
                
                if (vmResource?.node) {
                    log.debug("VM/template $vmId found on node: ${vmResource.node}")
                    return vmResource.node
                }
            }
            
            // Fallback: Check each node individually
            log.debug("VM/template $vmId not found in cluster resources, checking nodes individually")
            List<String> nodes = getProxmoxHypervisorNodeIds(client, authConfig).data
            
            for (String node : nodes) {
                try {
                    def vmConfig = callListApiV2(client, "nodes/$node/qemu/$vmId/config", authConfig)
                    if (vmConfig.success && vmConfig.data) {
                        log.debug("VM/template $vmId found on node $node via direct query")
                        return node
                    }
                } catch (Exception nodeEx) {
                    // VM not on this node, continue checking
                    log.debug("VM/template $vmId not on node $node")
                }
            }
            
            log.warn("VM/template $vmId not found on any node in the cluster")
            return null
        } catch (e) {
            log.error("Error finding node for VM $vmId: ${e}", e)
            return null
        }
    }

    /**
     * Get list of storage names available on a specific node
     */
    static List<String> getNodeStorages(HttpApiClient client, Map authConfig, String nodeId) {
        try {
            def storageResponse = callListApiV2(client, "nodes/${nodeId}/storage", authConfig)
            if (storageResponse?.data) {
                def storageNames = storageResponse.data.collect { it.storage }
                log.debug("Node $nodeId has storages: $storageNames")
                return storageNames
            }
            return []
        } catch (e) {
            log.error("Error getting storages for node $nodeId: ${e}", e)
            return []
        }
    }

    /**
     * Get the actual storage name used by a VM's root disk
     * @param client HttpApiClient
     * @param authConfig Authentication configuration
     * @param nodeId Node where VM resides
     * @param vmId VM ID
     * @return Storage name (e.g., "local-lvm", "Ceph.HDD.01")
     */
    static String getVMActualStorage(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        try {
            def vmStorageList = getExistingVMStorage(client, authConfig, nodeId, vmId)
            if (vmStorageList && vmStorageList.size() > 0) {
                def rootDisk = vmStorageList.find { it.isRoot }
                if (rootDisk?.config) {
                    // Disk config format: "storage:vm-123-disk-0,size=10G" or "storage:123/vm-123-disk-0.qcow2"
                    def diskConfig = rootDisk.config
                    def storageName = diskConfig.split(':')[0]
                    log.debug("VM $vmId on node $nodeId uses storage: $storageName")
                    return storageName
                }
            }
            log.warn("Could not determine actual storage for VM $vmId on node $nodeId")
            return null
        } catch (e) {
            log.error("Error getting actual storage for VM $vmId: ${e}", e)
            return null
        }
    }


    static startVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("startVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "start")
    }

    static rebootVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("rebootVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reboot")
    }

    static shutdownVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("shutdownVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "shutdown")
    }

    static stopVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("stopVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "stop")
    }

    static resetVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("resetVM")
        return actionVMStatus(client, authConfig, nodeId, vmId, "reset")
    }


    static actionVMStatus(HttpApiClient client, Map authConfig, String nodeId, String vmId, String action, int maxRetries = 3) {
        int retryCount = 0
        Exception lastException = null

        while (retryCount < maxRetries) {
            try {
                def tokenCfg = getApiV2Token(authConfig).data
                def opts = [
                        headers  : [
                                'Content-Type'       : 'application/json',
                                'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                                'CSRFPreventionToken': tokenCfg.csrfToken
                        ],
                        body     : [
                                vmid: vmId,
                                node: nodeId
                        ],
                        contentType: ContentType.APPLICATION_JSON,
                        ignoreSSL: true
                ]

                log.debug("Post path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/ (attempt ${retryCount + 1}/$maxRetries)")
                def results = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/status/$action/",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'POST'
                )

                if (results?.success) {
                    log.debug("VM $action successful on attempt ${retryCount + 1}")
                    return results
                } else if (retryCount < maxRetries - 1) {
                    log.warn("VM $action failed on attempt ${retryCount + 1}, retrying...")
                    sleep(2000) // Wait 2 seconds before retry
                    retryCount++
                } else {
                    return results
                }
            } catch (org.apache.http.NoHttpResponseException e) {
                lastException = e
                log.warn("NoHttpResponseException performing $action on VM $vmId (attempt ${retryCount + 1}/$maxRetries): ${e.message}")
                if (retryCount < maxRetries - 1) {
                    sleep(3000) // Wait 3 seconds before retry on connection error
                    retryCount++
                } else {
                    log.error("Failed to perform $action on VM $vmId after $maxRetries attempts", e)
                    return ServiceResponse.error("Error performing $action on VM after $maxRetries attempts: Connection to Proxmox failed")
                }
            } catch (Exception e) {
                lastException = e
                log.error("Error performing $action on VM $vmId (attempt ${retryCount + 1}/$maxRetries): ${e.message}", e)
                if (retryCount < maxRetries - 1) {
                    sleep(2000)
                    retryCount++
                } else {
                    return ServiceResponse.error("Error performing $action on VM: ${e.message}")
                }
            }
        }

        return ServiceResponse.error("Error performing $action on VM after $maxRetries attempts: ${lastException?.message}")
    }


    static destroyVM(HttpApiClient client, Map authConfig, String nodeId, String vmId) {
        log.debug("destroyVM")
        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body: null,
                    ignoreSSL: true,
                    contentType: ContentType.APPLICATION_JSON,
            ]

            log.debug("Delete Opts: $opts")
            log.debug("Delete path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/")

            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/",
                    new HttpApiClient.RequestOptions(opts),
                    'DELETE'
            )

            log.debug("VM Delete Response Details: ${results.toMap()}")
            return results

        //TODO - check for non 200 response
        } catch (e) {
            log.error "Error Destroying VM: ${e}", e
            return ServiceResponse.error("Error Destroying VM: ${e}")
        }
    }


    static createImageTemplate(HttpApiClient client, Map authConfig, String imageName, String nodeId, int cpu, Long ram, String sourceUri = null) {
        log.debug("createImage: $imageName")

        def rtn = new ServiceResponse(success: true)
        def nextId = callListApiV2(client, "cluster/nextid", authConfig).data
        log.debug("Next VM Id is: $nextId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            rtn.data = []
            def opts = [
                    headers  : [
                            'Content-Type'       : 'application/json',
                            'Cookie'             : "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    body     : [
                            vmid: nextId,
                            node: nodeId,
                            name: imageName.replaceAll(/\s+/, ''),
                            template: true,
                            scsihw: "virtio-scsi-single"
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Creating blank template for attaching qcow2...")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/")
            def results = client.callJsonApi(
                    (String) authConfig.apiUrl,
                    "${authConfig.v2basePath}/nodes/$nodeId/qemu/",
                    null, null,
                    new HttpApiClient.RequestOptions(opts),
                    'POST'
            )

            def resultData = results.data
            if (results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
                rtn.data.templateId = nextId
            } else {
                rtn.msg = "Template create failed: $results.data $results $results.errorCode $results.data"
                rtn.success = false
            }
        } catch (e) {
            log.error "Error Provisioning VM: ${e}", e
            return ServiceResponse.error("Error Provisioning VM: ${e}")
        }
        return rtn
    }


    static ServiceResponse waitForCloneToComplete(HttpApiClient client, Map authConfig, String templateId, String vmId, String nodeId, Long timeoutInSec) {
        Long timeout = timeoutInSec * 1000
        Long duration = 0
        log.debug("waitForCloneToComplete: $templateId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            log.debug("Checking VM Status after clone template $templateId to VM $vmId on node $nodeId")
            log.debug("Path is: $authConfig.apiUrl${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config")

            while (duration < timeout) {
                log.debug("Checking VM $vmId status on node $nodeId")
                def results = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/qemu/$vmId/config",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'GET'
                )

                if (!results.success) {
                    log.error("Error checking VM clone result status.")
                    return results
                }

                def resultData = results.data
                if (!resultData.data.containsKey("lock")) {
                    return results
                } else {
                    log.debug("VM Still Locked, wait ${API_CHECK_WAIT_INTERVAL}ms and check again...")
                }
                sleep(API_CHECK_WAIT_INTERVAL)
                duration += API_CHECK_WAIT_INTERVAL
            }
            return new ServiceResponse(success: false, msg: "Timeout", data: "Timeout")
        } catch(e) {
            log.error "Error Checking VM Clone Status: ${e}", e
            return ServiceResponse.error("Error Checking VM Clone Status: ${e}")
        }
    }

    /**
     * Wait for a Proxmox task (identified by UPID) to complete
     * @param client HttpApiClient
     * @param authConfig Authentication configuration
     * @param nodeId Node where the task is running
     * @param taskUPID Task UPID from Proxmox API
     * @param timeoutInSec Timeout in seconds
     * @return ServiceResponse
     */
    static ServiceResponse waitForTaskComplete(HttpApiClient client, Map authConfig, String nodeId, String taskUPID, Long timeoutInSec) {
        Long timeout = timeoutInSec * 1000
        Long duration = 0
        log.debug("waitForTaskComplete: $taskUPID on node $nodeId")

        try {
            def tokenCfg = getApiV2Token(authConfig).data
            def opts = [
                    headers: [
                            'Content-Type': 'application/json',
                            'Cookie': "PVEAuthCookie=$tokenCfg.token",
                            'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            ]

            // Poll task status
            while (duration < timeout) {
                log.debug("Checking task $taskUPID status on node $nodeId")
                def results = client.callJsonApi(
                        (String) authConfig.apiUrl,
                        "${authConfig.v2basePath}/nodes/$nodeId/tasks/$taskUPID/status",
                        null, null,
                        new HttpApiClient.RequestOptions(opts),
                        'GET'
                )

                if (!results.success) {
                    log.error("Error checking task status: ${results.errorCode}")
                    sleep(API_CHECK_WAIT_INTERVAL)
                    duration += API_CHECK_WAIT_INTERVAL
                    continue
                }

                def resultData = results.data
                def status = resultData?.data?.status
                
                log.debug("Task status: $status")
                
                if (status == "stopped") {
                    def exitstatus = resultData?.data?.exitstatus
                    if (exitstatus == "OK") {
                        log.debug("Task $taskUPID completed successfully")
                        return new ServiceResponse(success: true, data: resultData)
                    } else {
                        log.error("Task $taskUPID failed with exit status: $exitstatus")
                        return new ServiceResponse(success: false, msg: "Task failed with status: $exitstatus", data: resultData)
                    }
                } else {
                    log.debug("Task still running (status: $status), waiting ${API_CHECK_WAIT_INTERVAL}ms...")
                }
                
                sleep(API_CHECK_WAIT_INTERVAL)
                duration += API_CHECK_WAIT_INTERVAL
            }
            
            return new ServiceResponse(success: false, msg: "Task timeout after ${timeoutInSec}s")
        } catch(e) {
            log.error "Error checking task status: ${e}", e
            return ServiceResponse.error("Error checking task status: ${e}")
        }
    }


    static ServiceResponse getProxmoxDatastoresById(HttpApiClient client, Map authConfig, List storageIds) {

        List<Map> filteredDS = listProxmoxDatastores(client, authConfig).data.findAll { storageIds.contains(it.storage) }

        return new ServiceResponse(success: true, data: filteredDS)
    }


    static ServiceResponse listProxmoxDatastores(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxDatastores...")

        var allowedDatastores = ["rbd", "cifs", "zfspool", "nfs", "lvmthin", "lvm", "cephfs", "iscsi", "dir"]
        Collection<Map> validDatastores = []
        ServiceResponse datastoreResults = callListApiV2(client, "storage", authConfig)
        def queryNode = getProxmoxHypervisorNodeIds(client, authConfig).data[0]

        datastoreResults.data.each { Map ds ->
            if (allowedDatastores.contains(ds.type)) {
                if (ds.containsKey("nodes")) {
                    //some pools don't belong to any node, but api path needs node for status details
                    queryNode = ((String) ds.nodes).split(",")[0]
                } else {
                    ds.nodes = "all"
                }

                try {
                    ServiceResponse dsInfoResponse = callListApiV2(client, "nodes/${queryNode}/storage/${ds.storage}/status", authConfig)
                    
                    if (dsInfoResponse.success && dsInfoResponse.data instanceof Map) {
                        Map dsInfo = dsInfoResponse.data as Map
                        ds.total = dsInfo.total ?: 0
                        ds.avail = dsInfo.avail ?: 0
                        ds.used = dsInfo.used ?: 0
                        ds.enabled = dsInfo.enabled ?: 0
                    } else {
                        // Handle case where API call fails (like for offline nodes)
                        log.warn("Failed to get storage status for ${ds.storage} on node ${queryNode}, using defaults")
                        ds.total = 0
                        ds.avail = 0
                        ds.used = 0
                        ds.enabled = 0
                    }

                    validDatastores << ds
                    
                } catch (Exception e) {
                    log.error("Error getting datastore status for ${ds.storage} on node ${queryNode}: ${e.message}")
                    // Set default values and include the datastore anyway
                    ds.total = 0
                    ds.avail = 0
                    ds.used = 0
                    ds.enabled = 0
                    validDatastores << ds
                }
            } else {
                log.warn("Storage ${ds} ignored...")
            }
        }

        return new ServiceResponse(success: true, data: validDatastores)
    }



    static ServiceResponse listProxmoxNetworks(HttpApiClient client, Map authConfig, uniqueIfaces = false) {
        log.debug("listProxmoxNetworks...")

        Collection<Map> networks = []
        List<String> hosts = getProxmoxHypervisorNodeIds(client, authConfig).data

        hosts.each { host ->
            try {
                ServiceResponse hostNetworks = callListApiV2(client, "nodes/$host/network", authConfig)
                if (hostNetworks.success && hostNetworks.data) {
                    hostNetworks.data.each { Map network ->
                        if (['bridge', 'vlan'].contains(network?.type)) {
                            network.networkAddress = ""
                            if (network?.cidr) {
                                network.networkAddress = ProxmoxMiscUtil.getNetworkAddress(network.cidr)
                            } else if (network?.address && network?.netmask) {
                                network.networkAddress = ProxmoxMiscUtil.getNetworkAddress("$network.address/$network.netmask")
                            }
                            network.host = host
                            networks << network
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to get networks for host ${host}: ${e.message}")
            }
        }

        try {
            ServiceResponse sdnNetworks = callListApiV2(client, "cluster/sdn/vnets", authConfig)
            if (sdnNetworks.success && sdnNetworks.data) {
                log.debug("Found SDN Networks: ${sdnNetworks.data}")
                sdnNetworks.data.each { Map sdn ->
                    ServiceResponse sdnSubnets = callListApiV2(client, "cluster/sdn/vnets/${sdn.vnet}/subnets",
                            authConfig)
                    List<NetworkSubnet> subnets = []
                    if (sdnSubnets.success && sdnSubnets.data) {
                        log.debug("Found SDN Subnets for ${sdn.vnet}: ${sdnSubnets.data}")
                        sdnSubnets.data.each { Map subnet ->
                            log.debug("Saving values: name as ${subnet.subnet}, cidr as ${subnet.cidr}," +
                                    " gateway as ${subnet.gateway}")
                            NetworkSubnet subnetData = new NetworkSubnet(
                                name: subnet.subnet,
                                cidr: subnet.cidr,
                                netmask: subnet.mask,
                                subnetAddress: subnet.network,
                                gateway: subnet.gateway
                            )
                            subnets << subnetData
                        }
                    }
                    if (subnets.size() > 0) {
                        sdn.networkAddress = subnets[0].cidr
                        sdn.gateway = subnets[0].gateway
                        sdn.netmask = subnets[0].netmask
                        sdn.subnetAddress = subnets[0].subnetAddress
                    } else {
                        sdn.networkAddress = ""
                        sdn.gateway = ""
                        sdn.netmask = ""
                        sdn.subnetAddress = ""
                    }
                    sdn.subnets = subnets
                    sdn.iface = sdn.vnet
                    sdn.host = "all"
                    networks << sdn
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get SDN networks: ${e.message}")
        }

        if (uniqueIfaces) {
            Set seenIfaces = new HashSet<>()
            List<Map> uniqueNetworks = networks.findAll { map ->
                if (map.iface && !seenIfaces.contains(map.iface)) {
                    seenIfaces << map.iface
                    return true
                }
                return false
            }
            return new ServiceResponse(success: true, data: uniqueNetworks)
        }

        return new ServiceResponse(success: true, data: networks)
    }



    static ServiceResponse getTemplateById(HttpApiClient client, Map authConfig, Long templateId) {

        def resp = listTemplates(client, authConfig)
        def filteredTemplate = resp.data.find { it.vmid == templateId}

        return new ServiceResponse(success: resp.success, data: filteredTemplate)
    }


    static ServiceResponse listTemplates(HttpApiClient client, Map authConfig) {
        log.debug("API Util listTemplates")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 1 && vm?.type == "qemu") {
                vm.ip = ""
                def vmConfigInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                vm.maxCores = (vmConfigInfo?.data?.sockets?.toInteger() ?: 0) * (vmConfigInfo?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmConfigInfo?.data?.cores?.toInteger() ?: 0

                vm.datastores = vmConfigInfo.data?.findAll { k, v ->
                    // Match disk keys like 'virtio0', 'scsi0', 'ide1', etc.
                    k ==~ /^(virtio|scsi|ide|sata)\d+$/ && v instanceof String && v.contains(':')
                }.collect { k, v ->
                    // Extract the storage ID before the colon
                    v.split(':')[0]
                }.unique()

                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }


    static ServiceResponse listVMs(HttpApiClient client, Map authConfig) {
        log.debug("API Util listVMs")
        def vms = []
        def qemuVMs = callListApiV2(client, "cluster/resources", authConfig)
        qemuVMs.data.each { Map vm ->
            if (vm?.template == 0 && vm?.type == "qemu") {
                def vmAgentInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/agent/network-get-interfaces", authConfig)
                vm.ip = ""
                if (vmAgentInfo.success && vmAgentInfo.data?.result) {
                    def interfaces = vmAgentInfo.data.result
                    // Iterate through each network interface
                    interfaces.each { iface ->
                        if (iface.'ip-addresses') {
                            // Iterate through each IP address in the interface
                            iface.'ip-addresses'.each { ipAddr ->
                                def ipAddress = ipAddr.'ip-address'
                                def ipType = ipAddr.'ip-address-type'
                                if (ipType == "ipv4" && ipAddress != "127.0.0.1" && vm.ip == "") {
                                    log.debug("Setting IP address for VM ${vm.vmid}: ${ipAddress}")
                                    vm.ip = ipAddress
                                }
                            }
                        }
                    }
                }
                def vmConfigInfo = callListApiV2(client, "nodes/$vm.node/qemu/$vm.vmid/config", authConfig)
                vm.maxCores = (vmConfigInfo?.data?.data?.sockets?.toInteger() ?: 0) * (vmConfigInfo?.data?.data?.cores?.toInteger() ?: 0)
                vm.coresPerSocket = vmConfigInfo?.data?.data?.cores?.toInteger() ?: 0

               vm.datastores = vmConfigInfo.data?.data.findAll { k, v ->
                    // Match disk keys like 'virtio0', 'scsi0', 'ide1', etc.
                    k ==~ /^(virtio|scsi|ide|sata)\d+$/ && v instanceof String && v.contains(':')
                }.collect { k, v ->
                    // Extract the storage ID before the colon
                    v.split(':')[0]
                }.unique()

                vms << vm
            }
        }
        return new ServiceResponse(success: true, data: vms)
    }


    static Map getVMConfigById(HttpApiClient client, Map authConfig, String vmId, String nodeId = "0") {

        //proxmox api limitation. If we don't have the node we need to query all
        if (nodeId == 0) {
            Map vm = listVMs(client, authConfig).data.find { it.vmid == vmId }
            if (!vm) {
                throw new Exception("Error: VM with ID $vmId not found.")
            }
            nodeId = vm.node as Long
        }
        def vmConfigInfo = callListApiV2(client, "nodes/$nodeId/qemu/$vmId/config", authConfig)

        return vmConfigInfo.data.data
    }


    static ServiceResponse listProxmoxPools(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxPools...")
        def pools = []

        List<Map> poolIds = callListApiV2(client, "pools", authConfig).data

        poolIds.each { Map pool ->
            Map poolData = callListApiV2(client, "pools/$pool.poolid", authConfig).data
            pools << poolData
        }

        return new ServiceResponse(success: true, data: pools)
    }


    static ServiceResponse getProxmoxHypervisorHostByName(HttpApiClient client, Map authConfig, String nodeId) {
        def resp = listProxmoxHypervisorHosts(client, authConfig)
        def node = resp.data.find { it.node == nodeId }

        return new ServiceResponse(success: resp.success, data: node)
    }


    static ServiceResponse getProxmoxHypervisorNodeIds(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxHosts...")

        List<String> hvHostIds = callListApiV2(client, "nodes", authConfig).data.collect { it.node as String }

        return new ServiceResponse(success: true, data: hvHostIds)
    }


    static ServiceResponse listProxmoxHypervisorHosts(HttpApiClient client, Map authConfig) {
        log.debug("listProxmoxHosts...")

        // Workaround: Get networks safely with error handling
        List<Map> allInterfaces = []
        try {
            ServiceResponse networkResponse = listProxmoxNetworks(client, authConfig)
            if (networkResponse?.success && networkResponse?.data) {
                allInterfaces = networkResponse.data
            }
        } catch (Exception e) {
            log.warn("Failed to get network interfaces, continuing without them: ${e.message}")
            allInterfaces = []
        }
        
        // Get datastores safely with error handling  
        List<Map> allDatastores = []
        try {
            ServiceResponse datastoreResponse = listProxmoxDatastores(client, authConfig)
            if (datastoreResponse?.success && datastoreResponse?.data) {
                allDatastores = datastoreResponse.data
            }
        } catch (Exception e) {
            log.warn("Failed to get datastores, continuing without them: ${e.message}")
            allDatastores = []
        }

        def nodes = callListApiV2(client, "nodes", authConfig).data
        nodes.each { Map hvHost ->
            try {
                def nodeNetworkInfo = callListApiV2(client, "nodes/$hvHost.node/network", authConfig)
                
                // Check if network info was retrieved successfully
                if (!nodeNetworkInfo.success || !nodeNetworkInfo.data) {
                    log.warn("Failed to retrieve network info for node ${hvHost.node}, setting default IP")
                    hvHost.ipAddress = ""  // Set default IP as empty for offline nodes
                } else {
                    def sortedNetworks = nodeNetworkInfo.data.sort { a, b ->
                        def aIface = a?.iface
                        def bIface = b?.iface

                        // Push null/empty iface to the bottom
                        if (!aIface && bIface) return 1
                        if (!bIface && aIface) return -1
                        if (!aIface && !bIface) return 0

                        // Prioritize vmbr0
                        if (aIface == 'vmbr0') return -1
                        if (bIface == 'vmbr0') return 1

                        // Normal alphabetical sort
                        return aIface <=> bIface
                    }
                    
                    log.debug("Sorted Networks for node ${hvHost.node}: $sortedNetworks")
                    
                    // Find the first network interface with a valid address
                    def validInterface = sortedNetworks.find { it != null && it.address != null && it.address.trim() != "" }
                    
                    if (validInterface) {
                        hvHost.ipAddress = validInterface.address
                        log.debug("Set IP address for node ${hvHost.node}: ${hvHost.ipAddress}")
                    } else {
                        log.warn("No valid network interface found for node ${hvHost.node}, using node name as fallback")
                        hvHost.ipAddress = hvHost.node  // Use node name as fallback
                    }
                }

                // Set networks (with null checking and safe fallback)
                if (allInterfaces) {
                    hvHost.networks = allInterfaces
                            ?.findAll { it?.host == hvHost.node || it?.host == 'all' }
                            ?.collect { it?.iface }
                            ?.findAll { it != null } ?: []
                } else {
                    // Fallback: extract from direct node network call
                    hvHost.networks = nodeNetworkInfo?.data?.findAll { it?.iface }?.collect { it.iface } ?: []
                }

                // Set datastores (with null checking and safe fallback)
                if (allDatastores) {
                    hvHost.datastores = allDatastores
                            ?.findAll { ds -> 
                                def dsNodes = ds?.nodes?.toString()
                                return dsNodes && (dsNodes.split(",").contains(hvHost.node) || dsNodes == 'all')
                            }
                            ?.collect { it?.storage }
                            ?.findAll { it != null } ?: []
                } else {
                    // Fallback: try to get datastores directly for this node
                    try {
                        ServiceResponse nodeStorageResponse = callListApiV2(client, "nodes/${hvHost.node}/storage", authConfig)
                        if (nodeStorageResponse?.success && nodeStorageResponse?.data) {
                            hvHost.datastores = nodeStorageResponse.data.collect { it?.storage }.findAll { it != null }
                        } else {
                            hvHost.datastores = []
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get storage for node ${hvHost.node}: ${e.message}")
                        hvHost.datastores = []
                    }
                }
                        
            } catch (Exception e) {
                log.error("Error processing node ${hvHost.node}: ${e.message}", e)
                // Set default values for failed nodes
                hvHost.ipAddress = ""
                hvHost.networks = []
                hvHost.datastores = []
            }
        }

        return new ServiceResponse(success: true, data: nodes)
    }
    
    
    private static ServiceResponse callListApiV2(HttpApiClient client, String path, Map authConfig) {
        log.debug("callListApiV2: path: ${path}")

        def tokenCfg = getApiV2Token(authConfig).data
        def rtn = new ServiceResponse(success: false)
        try {
            rtn.data = []
            
            // Separate path and query string for proper HTTP client handling
            def pathParts = path.split('\\?', 2)
            def actualPath = "${authConfig.v2basePath}/${pathParts[0]}"
            def queryString = pathParts.length > 1 ? pathParts[1] : null
            
            log.debug("callListApiV2: actualPath=${actualPath}, queryString=${queryString}")
            
            def opts = new HttpApiClient.RequestOptions(
                    headers: [
                        'Content-Type': 'application/json',
                        'Cookie': "PVEAuthCookie=$tokenCfg.token",
                        'CSRFPreventionToken': tokenCfg.csrfToken
                    ],
                    contentType: ContentType.APPLICATION_JSON,
                    ignoreSSL: true
            )
            
            // Use 6-parameter version if we have a query string, otherwise use 4-parameter version
            def results = queryString ? 
                client.callJsonApi(authConfig.apiUrl, actualPath, queryString, null, opts, 'GET') :
                client.callJsonApi(authConfig.apiUrl, actualPath, null, null, opts, 'GET')
            
            def resultData = results.toMap().data.data
            log.debug("callListApiV2 results: ${resultData}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                rtn.data = resultData
            } else {
                if(!rtn.success) {
                    rtn.msg = results.data + results.errors
                    rtn.success = false
                }
            }
        } catch(e) {
            log.error "Error in callListApiV2: ${e}", e
            rtn.msg = "Error in callListApiV2: ${e}"
            rtn.success = false
        }
        return rtn
    }


    private static ServiceResponse getApiV2Token(Map authConfig) {
        def path = "access/ticket"
        //log.debug("getApiV2Token: path: ${path}")
        HttpApiClient client = new HttpApiClient()

        def rtn = new ServiceResponse(success: false)
        try {

            def encUid = URLEncoder.encode((String) authConfig.username, "UTF-8")
            def encPwd = URLEncoder.encode((String) authConfig.password, "UTF-8")
            def bodyStr = "username=" + "$encUid" + "&password=$encPwd"

            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions(
                    headers: ['Content-Type':'application/x-www-form-urlencoded'],
                    body: bodyStr,
                    contentType: ContentType.APPLICATION_FORM_URLENCODED,
                    ignoreSSL: true
            )
            def results = client.callJsonApi(authConfig.apiUrl,"${authConfig.v2basePath}/${path}", opts, 'POST')

            //log.debug("getApiV2Token API request results: ${results.toMap()}")
            if(results?.success && !results?.hasErrors()) {
                rtn.success = true
                def tokenData = results.data.data
                rtn.data = [csrfToken: tokenData.CSRFPreventionToken, token: tokenData.ticket]

            } else {
                rtn.success = false
                rtn.msg = "Error retrieving token: $results.data"
                log.error("Error retrieving token: $results.data")
            }
            return rtn
        } catch(e) {
            log.error "Error in getApiV2Token: ${e}", e
            rtn.msg = "Error in getApiV2Token: ${e}"
            rtn.success = false
        }
        return rtn
    }
}
