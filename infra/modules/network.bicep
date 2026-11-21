// Virtual network for the Container Apps environment and the PostgreSQL server (private access only).

@description('Name prefix, e.g. mm-payments-dev.')
param namePrefix string
param location string
param tags object

@description('Address space of the VNet, e.g. 10.40.0.0/22. Must not overlap with networks peered to the API Manager.')
param addressPrefix string

@description('Subnet for the Container Apps environment (workload profiles; /27 minimum, /26 recommended).')
param appsSubnetPrefix string

@description('Delegated subnet for PostgreSQL Flexible Server.')
param postgresSubnetPrefix string

resource vnet 'Microsoft.Network/virtualNetworks@2024-01-01' = {
  name: '${namePrefix}-vnet'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [addressPrefix]
    }
    subnets: [
      {
        name: 'snet-apps'
        properties: {
          addressPrefix: appsSubnetPrefix
          // Key Vault is reachable from this subnet only (Service Bus Standard has no VNet integration; see AZURE_SERVICES.md)
          serviceEndpoints: [
            { service: 'Microsoft.KeyVault' }
          ]
          delegations: [
            {
              name: 'aca'
              properties: { serviceName: 'Microsoft.App/environments' }
            }
          ]
        }
      }
      {
        name: 'snet-postgres'
        properties: {
          addressPrefix: postgresSubnetPrefix
          delegations: [
            {
              name: 'postgres'
              properties: { serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers' }
            }
          ]
        }
      }
    ]
  }
}

output vnetId string = vnet.id
output appsSubnetId string = vnet.properties.subnets[0].id
output postgresSubnetId string = vnet.properties.subnets[1].id
