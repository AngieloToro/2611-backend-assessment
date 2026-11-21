// Premium Payment Notifications - Azure infrastructure (resource group scope).
// One deployment per environment (dev, test, prod) serving the three countries (assumption A-11).
// Parameter files: infra/env/<environment>.bicepparam. Nothing here is deployed by the assessment.

targetScope = 'resourceGroup'

@allowed(['dev', 'test', 'prod'])
param environmentName string

param location string = resourceGroup().location

@description('Short organisation/workload prefix used in resource names.')
@maxLength(12)
param workload string = 'mm-payments'

// --- Existing platform resources (owned by the platform team) ---

@description('Resource id of the platform Log Analytics workspace.')
param logAnalyticsWorkspaceId string

@description('Resource id of the shared Azure Container Registry.')
param containerRegistryId string

@description('Image tag to deploy: the Git commit SHA built once by CI and promoted unchanged.')
param imageTag string

// --- Network ---

param vnetAddressPrefix string
param appsSubnetPrefix string
param postgresSubnetPrefix string
param internalEnvironment bool = true

// --- Countries and providers (add a country here; the shared flow does not change, NFR9) ---

@description('Enabled providers. secretName is the Key Vault secret holding the provider webhook secret.')
param providers array = [
  { name: 'quetzal', country: 'MX', secretName: 'quetzal-webhook-secret' }
  { name: 'andina', country: 'CO', secretName: 'andina-webhook-secret' }
  { name: 'austral', country: 'CL', secretName: 'austral-webhook-secret' }
]

// --- Sizing per environment ---

@allowed(['Standard', 'Premium'])
param serviceBusSku string = 'Standard'

param postgresSkuName string
@allowed(['Burstable', 'GeneralPurpose', 'MemoryOptimized'])
param postgresSkuTier string
param postgresStorageSizeGB int = 32
@allowed(['Disabled', 'ZoneRedundant', 'SameZone'])
param postgresHighAvailability string
param postgresBackupRetentionDays int
param postgresEntraAdminObjectId string
param postgresEntraAdminName string

param intakeMinReplicas int
param intakeMaxReplicas int
param processorMaxReplicas int
@maxValue(2)
param writerMaxReplicas int = 2

// --- Operations ---

param squadEmail string
@secure()
param onCallWebhookUrl string = ''
param monthlyBudgetUsd int
@description('First day of the first budget month (yyyy-MM-01). Fixed: a budget start date cannot move on redeploys.')
param budgetStartDate string

var namePrefix = '${workload}-${environmentName}'
var tags = {
  workload: 'premium-payment-notifications'
  environment: environmentName
  owner: 'payments-integration-squad'
  costCenter: 'payments'
  dataClassification: 'confidential-no-pii' // no payer personal data is stored (NFR6)
}

// --- Identities: one per component, so each gets only the permissions it needs ---

var components = ['intake', 'processor', 'writer', 'opsApi']

resource identities 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = [for c in components: {
  name: '${namePrefix}-id-${toLower(c)}'
  location: location
  tags: tags
}]

var identityById = {
  intake: { id: identities[0].id, clientId: identities[0].properties.clientId, principalId: identities[0].properties.principalId }
  processor: { id: identities[1].id, clientId: identities[1].properties.clientId, principalId: identities[1].properties.principalId }
  writer: { id: identities[2].id, clientId: identities[2].properties.clientId, principalId: identities[2].properties.principalId }
  opsApi: { id: identities[3].id, clientId: identities[3].properties.clientId, principalId: identities[3].properties.principalId }
}

// AcrPull on the shared registry for every component identity.
resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: last(split(containerRegistryId, '/'))
  scope: resourceGroup(split(containerRegistryId, '/')[2], split(containerRegistryId, '/')[4])
}

module acrPull 'modules/acr-pull.bicep' = {
  name: 'acr-pull-${environmentName}'
  scope: resourceGroup(split(containerRegistryId, '/')[2], split(containerRegistryId, '/')[4])
  params: {
    registryName: registry.name
    principalIds: [for i in range(0, length(components)): identities[i].properties.principalId]
  }
}

module network 'modules/network.bicep' = {
  name: 'network-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    addressPrefix: vnetAddressPrefix
    appsSubnetPrefix: appsSubnetPrefix
    postgresSubnetPrefix: postgresSubnetPrefix
  }
}

module serviceBus 'modules/servicebus.bicep' = {
  name: 'servicebus-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    sku: serviceBusSku
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    queueSenderPrincipalIds: [identityById.intake.principalId]
    queueReceiverPrincipalIds: [identityById.processor.principalId]
    topicSenderPrincipalIds: [identityById.processor.principalId]
    salesforceWriterPrincipalId: identityById.writer.principalId
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    appsSubnetId: network.outputs.appsSubnetId
    enablePurgeProtection: environmentName != 'dev'
    // Only intake (provider secrets) and writer (Salesforce secret) read secrets.
    secretReaderPrincipalIds: [identityById.intake.principalId, identityById.writer.principalId]
  }
}

module postgres 'modules/postgres.bicep' = {
  name: 'postgres-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    vnetId: network.outputs.vnetId
    delegatedSubnetId: network.outputs.postgresSubnetId
    skuName: postgresSkuName
    skuTier: postgresSkuTier
    storageSizeGB: postgresStorageSizeGB
    highAvailabilityMode: postgresHighAvailability
    backupRetentionDays: postgresBackupRetentionDays
    entraAdminObjectId: postgresEntraAdminObjectId
    entraAdminName: postgresEntraAdminName
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    environmentName: environmentName
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    squadEmail: squadEmail
    onCallWebhookUrl: onCallWebhookUrl
    monthlyBudgetUsd: monthlyBudgetUsd
    budgetStartDate: budgetStartDate
    serviceBusNamespaceId: serviceBus.outputs.namespaceId
    inboundQueueName: serviceBus.outputs.inboundQueueName
    eventsTopicName: serviceBus.outputs.eventsTopicName
  }
}

module apps 'modules/containerapps.bicep' = {
  name: 'containerapps-${environmentName}'
  params: {
    namePrefix: namePrefix
    location: location
    tags: tags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    appsSubnetId: network.outputs.appsSubnetId
    internalEnvironment: internalEnvironment
    containerRegistryLoginServer: registry.properties.loginServer
    imageTag: imageTag
    identities: identityById
    appInsightsConnectionString: monitoring.outputs.appInsightsConnectionString
    keyVaultUri: keyVault.outputs.vaultUri
    serviceBusNamespace: serviceBus.outputs.fullyQualifiedNamespace
    inboundQueueName: serviceBus.outputs.inboundQueueName
    eventsTopicName: serviceBus.outputs.eventsTopicName
    writerSubscriptionName: serviceBus.outputs.writerSubscriptionName
    postgresFqdn: postgres.outputs.fqdn
    postgresDatabase: postgres.outputs.databaseName
    providers: providers
    intakeMinReplicas: intakeMinReplicas
    intakeMaxReplicas: intakeMaxReplicas
    processorMaxReplicas: processorMaxReplicas
    writerMaxReplicas: writerMaxReplicas
  }
  dependsOn: [acrPull]
}

output intakeFqdn string = apps.outputs.intakeFqdn
output keyVaultName string = keyVault.outputs.vaultName
output serviceBusNamespace string = serviceBus.outputs.fullyQualifiedNamespace
output postgresServer string = postgres.outputs.serverName
output identityPrincipalIds object = {
  processor: identityById.processor.principalId
  writer: identityById.writer.principalId
  opsApi: identityById.opsApi.principalId
}
