// Observability for the integration: Application Insights (on the platform Log Analytics workspace),
// action group, alerts derived from the SLOs in docs/operations/OPERATIONS.md, and a cost budget (NFR8).

param namePrefix string
param location string
param tags object
param environmentName string
param logAnalyticsWorkspaceId string

@description('Email of the squad distribution list.')
param squadEmail string

@secure()
@description('Webhook of the on-call paging tool. Empty in dev/test: alerts only email the squad.')
param onCallWebhookUrl string

@description('Monthly budget in USD for the resource group.')
param monthlyBudgetUsd int

@description('First day of the budget period (yyyy-MM-01).')
param budgetStartDate string

@description('Freshness SLO threshold in seconds for the p95 alert (NFR2: 5 minutes).')
param freshnessP95ThresholdSeconds int = 300

param serviceBusNamespaceId string
param inboundQueueName string
param eventsTopicName string

var isProd = environmentName == 'prod'

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: '${namePrefix}-appi'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
    DisableLocalAuth: false // the Java agent uses the connection string; ingestion with Entra ID is a follow-up
    IngestionMode: 'LogAnalytics'
  }
}

resource actionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: '${namePrefix}-ag'
  location: 'global'
  tags: tags
  properties: {
    groupShortName: take('pay${environmentName}', 12)
    enabled: true
    emailReceivers: [
      {
        name: 'payments-squad'
        emailAddress: squadEmail
        useCommonAlertSchema: true
      }
    ]
    webhookReceivers: empty(onCallWebhookUrl) ? [] : [
      {
        name: 'on-call'
        serviceUri: onCallWebhookUrl
        useCommonAlertSchema: true
      }
    ]
  }
}

// --- Platform metric alerts (Service Bus) ---

resource dlqAlert 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: '${namePrefix}-dlq-not-empty'
  location: 'global'
  tags: tags
  properties: {
    description: 'Messages in a dead-letter queue of the integration. Every country shares these entities, so the alert covers MX, CO and CL (INC-0412 lesson).'
    severity: isProd ? 1 : 3
    enabled: true
    scopes: [serviceBusNamespaceId]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT5M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'deadlettered'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          metricName: 'DeadletteredMessages'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [inboundQueueName, eventsTopicName]
            }
          ]
          operator: 'GreaterThan'
          threshold: 0
          timeAggregation: 'Maximum'
        }
      ]
    }
    actions: [
      { actionGroupId: actionGroup.id }
    ]
  }
}

resource backlogAlert 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: '${namePrefix}-backlog-growing'
  location: 'global'
  tags: tags
  properties: {
    description: 'Active messages above 5,000 for 15 minutes: processing or Salesforce writing is not keeping up (freshness at risk).'
    severity: isProd ? 2 : 3
    enabled: true
    scopes: [serviceBusNamespaceId]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          criterionType: 'StaticThresholdCriterion'
          name: 'active'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          metricName: 'ActiveMessages'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [inboundQueueName, eventsTopicName]
            }
          ]
          operator: 'GreaterThan'
          threshold: 5000
          timeAggregation: 'Minimum'
        }
      ]
    }
    actions: [
      { actionGroupId: actionGroup.id }
    ]
  }
}

// --- Application alerts per country (log search on Application Insights telemetry) ---

var logAlerts = [
  {
    name: 'freshness-p95'
    description: 'p95 time from provider notification to Salesforce above the 5-minute SLO, per country (NFR2).'
    severity: isProd ? 2 : 3
    query: 'customEvents | where name == "PaymentRecorded" | extend country = tostring(customDimensions.country), freshness = todouble(customDimensions.freshnessSeconds) | summarize p95 = percentile(freshness, 95) by country'
    measure: 'p95'
    aggregation: 'Maximum'
    threshold: freshnessP95ThresholdSeconds
    window: 'PT15M'
  }
  {
    name: 'dead-lettered-by-country'
    description: 'Notifications dead-lettered in the last 15 minutes, per country and reason.'
    severity: isProd ? 1 : 3
    query: 'customEvents | where name == "NotificationDeadLettered" | extend country = tostring(customDimensions.country), reason = tostring(customDimensions.reason) | summarize dead = count() by country, reason'
    measure: 'dead'
    aggregation: 'Total'
    threshold: 0
    window: 'PT15M'
  }
  {
    name: 'unsynced-payments'
    description: 'Payments accepted more than 1 hour ago and not yet in Salesforce (loss risk, NFR3/NFR7), reported by the reconciliation job.'
    severity: isProd ? 1 : 3
    query: 'customMetrics | where name == "payments.reconciliation.unsynced" | extend country = tostring(customDimensions.country) | summarize unsynced = max(value) by country'
    measure: 'unsynced'
    aggregation: 'Maximum'
    threshold: 0
    window: 'PT1H'
  }
  {
    name: 'signature-failures'
    description: 'More than 20 rejected signatures in 10 minutes for a provider: secret rotation issue or attack.'
    severity: isProd ? 2 : 3
    query: 'customMetrics | where name == "payments.intake.notifications" | extend country = tostring(customDimensions.country), outcome = tostring(customDimensions.outcome) | where outcome == "invalid_signature" | summarize failures = sum(valueCount) by country'
    measure: 'failures'
    aggregation: 'Total'
    threshold: 20
    window: 'PT10M'
  }
  {
    name: 'salesforce-budget'
    description: 'Salesforce API usage of this integration above 70 % of its 150,000 calls / 24 h budget (writer halts at 80 %).'
    severity: isProd ? 2 : 3
    query: 'customMetrics | where name == "payments.salesforce.budget.used.percent" | summarize used = max(value)'
    measure: 'used'
    aggregation: 'Maximum'
    threshold: 70
    window: 'PT15M'
  }
]

resource scheduledAlerts 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = [for alert in logAlerts: {
  name: '${namePrefix}-${alert.name}'
  location: location
  tags: tags
  properties: {
    description: alert.description
    severity: alert.severity
    enabled: true
    scopes: [appInsights.id]
    evaluationFrequency: 'PT5M'
    windowSize: alert.window
    criteria: {
      allOf: [
        {
          query: alert.query
          metricMeasureColumn: alert.measure
          timeAggregation: alert.aggregation
          operator: 'GreaterThan'
          threshold: alert.threshold
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: {
      actionGroups: [actionGroup.id]
    }
  }
}]

resource budget 'Microsoft.Consumption/budgets@2023-05-01' = {
  name: '${namePrefix}-monthly-budget'
  properties: {
    category: 'Cost'
    amount: monthlyBudgetUsd
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: budgetStartDate
    }
    notifications: {
      actual80: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 80
        thresholdType: 'Actual'
        contactEmails: [squadEmail]
      }
      forecast100: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 100
        thresholdType: 'Forecasted'
        contactEmails: [squadEmail]
      }
    }
  }
}

output appInsightsConnectionString string = appInsights.properties.ConnectionString
output actionGroupId string = actionGroup.id
