using '../main.bicep'

// Production: zone-redundant database, always-warm intake, on-call paging.
param environmentName = 'prod'
param imageTag = readEnvironmentVariable('IMAGE_TAG')

param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/law-platform-prod'
param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/acrmeridianshared'

param vnetAddressPrefix = '10.42.0.0/22'
param appsSubnetPrefix = '10.42.0.0/26'
param postgresSubnetPrefix = '10.42.1.0/28'

param serviceBusSku = 'Standard'
param postgresSkuName = 'Standard_D2ds_v5'
param postgresSkuTier = 'GeneralPurpose'
param postgresStorageSizeGB = 64
param postgresHighAvailability = 'ZoneRedundant'
param postgresBackupRetentionDays = 35
param postgresEntraAdminObjectId = '22222222-2222-2222-2222-222222222222'
param postgresEntraAdminName = 'grp-payments-db-admins-prod'

param intakeMinReplicas = 2
param intakeMaxReplicas = 10
param processorMaxReplicas = 5
param writerMaxReplicas = 2

param squadEmail = 'payments-squad@meridian.example'
// Injected by the pipeline from a GitHub environment secret; never stored in the repository.
param onCallWebhookUrl = readEnvironmentVariable('ONCALL_WEBHOOK_URL', '')
param monthlyBudgetUsd = 700
param budgetStartDate = '2027-01-01'
