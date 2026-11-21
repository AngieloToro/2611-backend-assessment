using '../main.bicep'

// Test: production topology at reduced size, used for load tests of the month-end peak.
param environmentName = 'test'
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local')

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/law-platform-nonprod'
param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/acrmeridianshared'

param vnetAddressPrefix = '10.41.0.0/22'
param appsSubnetPrefix = '10.41.0.0/26'
param postgresSubnetPrefix = '10.41.1.0/28'

param serviceBusSku = 'Standard'
param postgresSkuName = 'Standard_B2s'
param postgresSkuTier = 'Burstable'
param postgresHighAvailability = 'Disabled'
param postgresBackupRetentionDays = 7
param postgresEntraAdminObjectId = '11111111-1111-1111-1111-111111111111'
param postgresEntraAdminName = 'grp-payments-db-admins-nonprod'

param intakeMinReplicas = 2
param intakeMaxReplicas = 5
param processorMaxReplicas = 3
param writerMaxReplicas = 2

param squadEmail = 'payments-squad@meridian.example'
param monthlyBudgetUsd = 250
param budgetStartDate = '2027-01-01'
