// PostgreSQL Flexible Server: inbox, payment state and outbox (SOLUTION_DESIGN section 8).
// Private access only (VNet integration) and Microsoft Entra ID authentication only (no passwords).

param namePrefix string
param location string
param tags object
param logAnalyticsWorkspaceId string

param vnetId string
param delegatedSubnetId string

@description('Compute SKU, e.g. Standard_B1ms (Burstable) for dev/test, Standard_D2ds_v5 (General Purpose) for prod.')
param skuName string

@allowed(['Burstable', 'GeneralPurpose', 'MemoryOptimized'])
param skuTier string

param storageSizeGB int

@allowed(['Disabled', 'ZoneRedundant', 'SameZone'])
param highAvailabilityMode string

@minValue(7)
@maxValue(35)
param backupRetentionDays int

@description('Object id of the Entra ID group that administers the server (the platform DBA group).')
param entraAdminObjectId string

@description('Display name of that Entra ID group.')
param entraAdminName string

resource dnsZone 'Microsoft.Network/privateDnsZones@2020-06-01' = {
  name: '${namePrefix}-pg.private.postgres.database.azure.com'
  location: 'global'
  tags: tags
}

resource dnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = {
  parent: dnsZone
  name: 'vnet-link'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: { id: vnetId }
  }
}

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: '${namePrefix}-pg'
  location: location
  tags: tags
  sku: {
    name: skuName
    tier: skuTier
  }
  properties: {
    version: '16'
    storage: {
      storageSizeGB: storageSizeGB
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: 'Disabled' // single region by design (SCENARIO); revisit with DR requirements
    }
    highAvailability: {
      mode: highAvailabilityMode
    }
    network: {
      delegatedSubnetResourceId: delegatedSubnetId
      privateDnsZoneArmResourceId: dnsZone.id
      publicNetworkAccess: 'Disabled'
    }
    authConfig: {
      activeDirectoryAuth: 'Enabled'
      passwordAuth: 'Disabled'
      tenantId: subscription().tenantId
    }
  }
  dependsOn: [dnsLink]
}

resource admin 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2024-08-01' = {
  parent: server
  name: entraAdminObjectId
  properties: {
    principalType: 'Group'
    principalName: entraAdminName
    tenantId: subscription().tenantId
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: 'payments'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

// Enforce TLS for every connection.
resource requireTls 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: server
  name: 'require_secure_transport'
  properties: {
    value: 'on'
    source: 'user-override'
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: server
  name: 'to-log-analytics'
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      { categoryGroup: 'allLogs', enabled: true }
    ]
    metrics: [
      { category: 'AllMetrics', enabled: true }
    ]
  }
}

output serverName string = server.name
output fqdn string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
