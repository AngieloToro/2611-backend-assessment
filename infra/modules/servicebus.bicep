// Service Bus: inbound queue (intake -> processor) and canonical event topic (processor -> consumers).
// Entra ID only (local/SAS auth disabled). Dead-lettering configured on every entity.

param namePrefix string
param location string
param tags object

@allowed(['Standard', 'Premium'])
@description('Standard is enough for ~40 msg/s; Premium adds private endpoints and isolation (see AZURE_SERVICES.md).')
param sku string

param logAnalyticsWorkspaceId string

@description('Principal ids that may send to the inbound queue (intake).')
param queueSenderPrincipalIds array

@description('Principal ids that may receive from the inbound queue (processor).')
param queueReceiverPrincipalIds array

@description('Principal ids that may publish to the topic (processor).')
param topicSenderPrincipalIds array

@description('Principal id of the Salesforce writer, the only receiver of its subscription.')
param salesforceWriterPrincipalId string

var roleDataSender = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39')
var roleDataReceiver = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0')

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: '${namePrefix}-sb'
  location: location
  tags: tags
  sku: {
    name: sku
    tier: sku
  }
  properties: {
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
  }
}

resource inboundQueue 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = {
  parent: namespace
  name: 'payment-notifications'
  properties: {
    requiresDuplicateDetection: true
    duplicateDetectionHistoryTimeWindow: 'P7D' // covers Quetzal 3 d, Austral 48 h, Andina 24 h retries
    lockDuration: 'PT1M'
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P14D'
    deadLetteringOnMessageExpiration: true
    enablePartitioning: false
    maxSizeInMegabytes: 5120
  }
}

resource eventsTopic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: 'payment-events'
  properties: {
    requiresDuplicateDetection: true
    duplicateDetectionHistoryTimeWindow: 'P1D' // outbox re-publication happens within minutes
    defaultMessageTimeToLive: 'P7D'
    maxSizeInMegabytes: 5120
  }
}

// Our own subscription. Finance and Notifications create and own theirs (FR6), with their own identities.
resource writerSubscription 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = {
  parent: eventsTopic
  name: 'salesforce-writer'
  properties: {
    lockDuration: 'PT2M' // a composite upsert of 200 records takes up to ~8 s; margin for retries inside the lock
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P7D' // survives the longest Salesforce outage with a wide margin
    deadLetteringOnMessageExpiration: true
    deadLetteringOnFilterEvaluationExceptions: true
  }
}

// --- Least-privilege data-plane RBAC, scoped to the entity, never to the namespace ---

resource queueSenders 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in queueSenderPrincipalIds: {
  scope: inboundQueue
  name: guid(inboundQueue.id, principalId, roleDataSender)
  properties: {
    roleDefinitionId: roleDataSender
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource queueReceivers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in queueReceiverPrincipalIds: {
  scope: inboundQueue
  name: guid(inboundQueue.id, principalId, roleDataReceiver)
  properties: {
    roleDefinitionId: roleDataReceiver
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource topicSenders 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in topicSenderPrincipalIds: {
  scope: eventsTopic
  name: guid(eventsTopic.id, principalId, roleDataSender)
  properties: {
    roleDefinitionId: roleDataSender
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource writerReceiver 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: writerSubscription
  name: guid(writerSubscription.id, salesforceWriterPrincipalId, roleDataReceiver)
  properties: {
    roleDefinitionId: roleDataReceiver
    principalId: salesforceWriterPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: namespace
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

output namespaceId string = namespace.id
output namespaceName string = namespace.name
output fullyQualifiedNamespace string = '${namespace.name}.servicebus.windows.net'
output inboundQueueName string = inboundQueue.name
output eventsTopicName string = eventsTopic.name
output writerSubscriptionName string = writerSubscription.name
