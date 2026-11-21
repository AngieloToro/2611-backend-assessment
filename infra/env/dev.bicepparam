using '../main.bicep'

// Development: smallest sizes, no HA, scale to minimum, alerts by email only.
param environmentName = 'dev'
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local')

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/law-platform-nonprod'
param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/acrmeridianshared'

param vnetAddressPrefix = '10.40.0.0/22'
param appsSubnetPrefix = '10.40.0.0/26'
param postgresSubnetPrefix = '10.40.1.0/28'

param serviceBusSku = 'Standard'
param postgresSkuName = 'Standard_B1ms'
param postgresSkuTier = 'Burstable'
param postgresHighAvailability = 'Disabled'
param postgresBackupRetentionDays = 7
param postgresEntraAdminObjectId = '11111111-1111-1111-1111-111111111111'
param postgresEntraAdminName = 'grp-payments-db-admins-nonprod'

param intakeMinReplicas = 1
param intakeMaxReplicas = 2
param processorMaxReplicas = 2
param writerMaxReplicas = 1

param squadEmail = 'payments-squad@meridian.example'
param monthlyBudgetUsd = 150
param budgetStartDate = '2027-01-01'
