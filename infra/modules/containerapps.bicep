// Container Apps environment and the four components of the design.
// Each app runs with its own user-assigned managed identity; no connection strings or keys are used.

param namePrefix string
param location string
param tags object
param logAnalyticsWorkspaceId string
param appsSubnetId string

@description('Internal environment: apps are reachable only through the VNet (the API Manager connects privately, assumption A-2).')
param internalEnvironment bool

param containerRegistryLoginServer string
param imageTag string

param identities object // { intake: {id, clientId}, processor: {...}, writer: {...}, opsApi: {...} }

@secure()
param appInsightsConnectionString string

param keyVaultUri string
param serviceBusNamespace string
param inboundQueueName string
param eventsTopicName string
param writerSubscriptionName string
param postgresFqdn string
param postgresDatabase string

@description('Enabled providers: [{ name: "quetzal", country: "MX", secretName: "quetzal-webhook-secret" }, ...]')
param providers array

param intakeMinReplicas int
param intakeMaxReplicas int
param processorMaxReplicas int

@description('Hard cap for the Salesforce writer: protects the 25-concurrent-requests agreement (ADR-0001).')
@maxValue(2)
param writerMaxReplicas int

resource environment 'Microsoft.App/managedEnvironments@2025-01-01' = {
  name: '${namePrefix}-cae'
  location: location
  tags: tags
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: appsSubnetId
      internal: internalEnvironment
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    // Logs go through Azure Monitor diagnostic settings, so no workspace shared key is needed.
    appLogsConfiguration: {
      destination: 'azure-monitor'
    }
    zoneRedundant: false
  }
}

resource environmentDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: environment
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

var image = '${containerRegistryLoginServer}/payments-integration:${imageTag}'

var commonEnv = [
  { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', secretRef: 'appinsights-connection-string' }
  { name: 'AZURE_SERVICEBUS_NAMESPACE', value: serviceBusNamespace }
]

var dbEnv = [
  { name: 'PAYMENTS_DB_HOST', value: postgresFqdn }
  { name: 'PAYMENTS_DB_NAME', value: postgresDatabase }
]

var probes = [
  {
    type: 'Liveness'
    httpGet: { path: '/actuator/health/liveness', port: 8080 }
    periodSeconds: 10
  }
  {
    type: 'Readiness'
    httpGet: { path: '/actuator/health/readiness', port: 8080 }
    periodSeconds: 5
  }
]

// Webhook secrets as Key Vault references resolved by the platform with the intake identity.
var providerSecrets = [for p in providers: {
  name: '${p.name}-webhook-secret'
  keyVaultUrl: '${keyVaultUri}secrets/${p.secretName}'
  identity: identities.intake.id
}]
var providerSecretEnv = [for p in providers: {
  name: 'PAYMENTS_INTAKE_PROVIDERS_${toUpper(p.name)}_SECRETS_0'
  secretRef: '${p.name}-webhook-secret'
}]

resource intake 'Microsoft.App/containerApps@2025-01-01' = {
  name: '${namePrefix}-intake'
  location: location
  tags: union(tags, { component: 'payments-intake' })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identities.intake.id}': {} }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true // "external" to the environment; the environment itself is internal to the VNet
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        { server: containerRegistryLoginServer, identity: identities.intake.id }
      ]
      secrets: concat(providerSecrets, [
        { name: 'appinsights-connection-string', value: appInsightsConnectionString }
      ])
    }
    template: {
      containers: [
        {
          name: 'intake'
          image: image
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: concat(commonEnv, providerSecretEnv, [
            { name: 'SPRING_PROFILES_ACTIVE', value: 'intake' }
            { name: 'AZURE_CLIENT_ID', value: identities.intake.clientId }
            { name: 'PAYMENTS_INTAKE_QUEUE', value: inboundQueueName }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: intakeMinReplicas // >= 2 in prod: no cold start against Andina's 5 s timeout
        maxReplicas: intakeMaxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: { metadata: { concurrentRequests: '50' } }
          }
        ]
      }
    }
  }
}

resource processor 'Microsoft.App/containerApps@2025-01-01' = {
  name: '${namePrefix}-processor'
  location: location
  tags: union(tags, { component: 'payments-processor' })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identities.processor.id}': {} }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        { server: containerRegistryLoginServer, identity: identities.processor.id }
      ]
      secrets: [
        { name: 'appinsights-connection-string', value: appInsightsConnectionString }
      ]
    }
    template: {
      containers: [
        {
          name: 'processor'
          image: image
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: concat(commonEnv, dbEnv, [
            { name: 'SPRING_PROFILES_ACTIVE', value: 'processor' }
            { name: 'AZURE_CLIENT_ID', value: identities.processor.clientId }
            { name: 'PAYMENTS_INBOUND_QUEUE', value: inboundQueueName }
            { name: 'PAYMENTS_EVENTS_TOPIC', value: eventsTopicName }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: processorMaxReplicas
        rules: [
          {
            name: 'inbound-queue-length'
            custom: {
              type: 'azure-servicebus'
              identity: identities.processor.id // KEDA authenticates with the managed identity
              metadata: {
                namespace: serviceBusNamespace
                queueName: inboundQueueName
                messageCount: '100'
              }
            }
          }
        ]
      }
    }
  }
}

resource writer 'Microsoft.App/containerApps@2025-01-01' = {
  name: '${namePrefix}-sf-writer'
  location: location
  tags: union(tags, { component: 'payments-sf-writer' })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identities.writer.id}': {} }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        { server: containerRegistryLoginServer, identity: identities.writer.id }
      ]
      secrets: [
        { name: 'appinsights-connection-string', value: appInsightsConnectionString }
        {
          name: 'salesforce-client-secret'
          keyVaultUrl: '${keyVaultUri}secrets/salesforce-client-secret'
          identity: identities.writer.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'sf-writer'
          image: image
          resources: { cpu: json('0.5'), memory: '1Gi' }
          env: concat(commonEnv, dbEnv, [
            { name: 'SPRING_PROFILES_ACTIVE', value: 'sf-writer' }
            { name: 'AZURE_CLIENT_ID', value: identities.writer.clientId }
            { name: 'PAYMENTS_EVENTS_TOPIC', value: eventsTopicName }
            { name: 'PAYMENTS_WRITER_SUBSCRIPTION', value: writerSubscriptionName }
            { name: 'SALESFORCE_CLIENT_SECRET', secretRef: 'salesforce-client-secret' }
            { name: 'PAYMENTS_WRITER_BATCH_SIZE', value: '200' }
            { name: 'PAYMENTS_WRITER_FLUSH_INTERVAL', value: 'PT5S' }
            { name: 'PAYMENTS_WRITER_BUDGET_GUARD_PERCENT', value: '80' }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: writerMaxReplicas
        rules: [
          {
            name: 'writer-subscription-length'
            custom: {
              type: 'azure-servicebus'
              identity: identities.writer.id
              metadata: {
                namespace: serviceBusNamespace
                topicName: eventsTopicName
                subscriptionName: writerSubscriptionName
                messageCount: '400'
              }
            }
          }
        ]
      }
    }
  }
}

resource opsApi 'Microsoft.App/containerApps@2025-01-01' = {
  name: '${namePrefix}-ops-api'
  location: location
  tags: union(tags, { component: 'payments-ops-api' })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identities.opsApi.id}': {} }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false // reachable only inside the environment / through the API Manager's private path
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        { server: containerRegistryLoginServer, identity: identities.opsApi.id }
      ]
      secrets: [
        { name: 'appinsights-connection-string', value: appInsightsConnectionString }
      ]
    }
    template: {
      containers: [
        {
          name: 'ops-api'
          image: image
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          env: concat(commonEnv, dbEnv, [
            { name: 'SPRING_PROFILES_ACTIVE', value: 'ops-api' }
            { name: 'AZURE_CLIENT_ID', value: identities.opsApi.clientId }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: 0
        maxReplicas: 2
      }
    }
  }
}

output environmentName string = environment.name
output intakeFqdn string = intake.properties.configuration.ingress.fqdn
output appNames array = [intake.name, processor.name, writer.name, opsApi.name]
