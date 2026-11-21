// Key Vault for provider webhook secrets and the Salesforce client secret.
// Secret VALUES are never in IaC: they are set out of band by the secret owner (see infra/README.md).

param namePrefix string
param location string
param tags object
param logAnalyticsWorkspaceId string

@description('Subnet of the Container Apps environment, the only network allowed through the firewall.')
param appsSubnetId string

@description('Purge protection cannot be disabled once enabled; on in test and prod.')
param enablePurgeProtection bool

@description('Principal ids that read secrets at runtime (Key Vault Secrets User).')
param secretReaderPrincipalIds array

var roleSecretsUser = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  // Key Vault names are max 24 characters and globally unique.
  name: take('${replace(namePrefix, '-', '')}kv${uniqueString(resourceGroup().id)}', 24)
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true // no access policies
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    enablePurgeProtection: enablePurgeProtection ? true : null
    // Firewall: deny by default, allow only the Container Apps subnet (service endpoint). Secret owners set
    // values through an approved jump host / IP rule added temporarily, never from the pipeline.
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Deny'
      virtualNetworkRules: [
        { id: appsSubnetId }
      ]
    }
  }
}

// Scoped to the vault. The vault only holds this integration's secrets; per-secret scoping is added once the
// secrets exist (they are created out of band, so they cannot be referenced here).
resource readers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in secretReaderPrincipalIds: {
  scope: vault
  name: guid(vault.id, principalId, roleSecretsUser)
  properties: {
    roleDefinitionId: roleSecretsUser
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: vault
  name: 'to-log-analytics'
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      { categoryGroup: 'audit', enabled: true }
    ]
    metrics: [
      { category: 'AllMetrics', enabled: true }
    ]
  }
}

output vaultName string = vault.name
output vaultUri string = vault.properties.vaultUri
