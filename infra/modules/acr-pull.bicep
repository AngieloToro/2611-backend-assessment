// AcrPull on the shared container registry (deployed in the registry's resource group).

param registryName string
param principalIds array

var roleAcrPull = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: registryName
}

resource pull 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in principalIds: {
  scope: registry
  name: guid(registry.id, principalId, roleAcrPull)
  properties: {
    roleDefinitionId: roleAcrPull
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]
