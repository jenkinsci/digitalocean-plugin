Allow Jenkins to start agents on [DigitalOcean](http://digitalocean.com/) droplets on demand and destroy them as they get unused.

# Configure plugin via Groovy script

Either automatically upon [Jenkins post-initialization](https://www.jenkins.io/doc/book/managing/groovy-hook-scripts/) or
through [Jenkins script console](https://www.jenkins.io/doc/book/managing/script-console/), example:

```groovy
import com.dubture.jenkins.digitalocean.DigitalOceanCloud
import com.dubture.jenkins.digitalocean.SlaveTemplate
import jenkins.model.Jenkins

// parameters
def agentTemplateParameters = [
  idleTerminationInMinutes: '10',
  imageId:                  'ubuntu-17-10-x64',
  initScript:               '',
  installMonitoring:        false,
  instanceCap:              '2',
  labellessJobsAllowed:     false,
  labelString:              'digitalocean.toronto.ubuntu.17.10',
  name:                     'digitalocean.toronto.ubuntu.17.10',
  numExecutors:             '1',
  regionId:                 'tor1',
  sizeId:                   '512mb',
  sshPort:                  22,
  tags:                     '',
  userData:                 '',
  username:                 'root',
  workspacePath:            '/home/jenkins'
]

def cloudParameters = [
  authToken:            '01234567890123456789012345678901234567890123456789',
  connectionRetryWait:  '30',
  instanceCap:          '2',
  name:                 'mycompany',
  sshKeyId:             '01234567',
  timeoutMinutes:       '10',
  usePrivateNetworking: false,
  privateKey:     '''-----BEGIN RSA PRIVATE KEY-----
MIICWwIBAAKBgGuoiHtwl8T2cKfclsWLOcv8S6p74iOAQX1kwCLvLy7ioDFlNzsI
U235N1StnZYZIwGla+3Uo3jMSUuWkMH85+d3KoRFPS+6RJCiAvMI0hr8FByes22v
DAVDnhkZ2SFOeh1SPxWDygPo2fW5sgqL2eYLO1CplDdqYhHLAL1FDV5tAgMBAAEC
gYBWRZoJgXK9zdb9TZIs/6LzSlzAY8IWPOM+PwyRcibXZZiFvNyDm+pviHTEkNRl
wgMBgLR6xBmz5dEel6utKKQVEPtD1m6N+z6hwUw+Nis35DCvmBX+hQSK+atGgjYH
ZKz0oqWUSuzHG+CxxcrePDTYJ4fdSyLPsQqaWoCZseDDwQJBANLey9r+juBEQe2N
MJoZTU1q/AoS5kY7OWQ1aF495I9fz87u9vx8BJh8djvmABwidUWREnd4vwwEIS3M
JtFGn+kCQQCCsvBvOXgVAlcR54/6ro6R42/0F3bZw0ZFVXvgRRjCZW6m4FyHq4AL
+gfAV0HERkMdlO1zBpBwkSURekDc9NvlAkAA3zj6k9jlZoLbR50u1fHy4wFdzUw0
eCQ5nNrsoNbkHOJQGb7dtmmSc9lNUBsqAp53hi0MX2xy0UWN2e1DKkaZAkBi9stH
7OQYRGVZkVVcI8Cghu7GjN3ZlhsndMsPzkIpMFTQ1yI5OIsEhpZH9co+rFU1mQcT
Ce1kzwKacU+b/2xhAkEAovqzUMFB9YEbc8C9AzTej5F2ttyuKBDJJ+kvQeJP+PnW
4ovFI4Ee5UmTWI6k/Md9BM+MvEMWs3nPoF4MULHqNg==
-----END RSA PRIVATE KEY-----'''
]

// https://github.com/jenkinsci/digitalocean-plugin/blob/digitalocean-plugin-0.17/src/main/java/com/dubture/jenkins/digitalocean/SlaveTemplate.java
SlaveTemplate agentTemplate = new SlaveTemplate(
  agentTemplateParameters.name,
  agentTemplateParameters.imageId,
  agentTemplateParameters.sizeId,
  agentTemplateParameters.regionId,
  agentTemplateParameters.username,
  agentTemplateParameters.workspacePath,
  agentTemplateParameters.sshPort,
  agentTemplateParameters.idleTerminationInMinutes,
  agentTemplateParameters.numExecutors,
  agentTemplateParameters.labelString,
  agentTemplateParameters.labellessJobsAllowed,
  agentTemplateParameters.instanceCap,
  agentTemplateParameters.installMonitoring,
  agentTemplateParameters.tags,
  agentTemplateParameters.userData,
  agentTemplateParameters.initScript
)

// https://github.com/jenkinsci/digitalocean-plugin/blob/digitalocean-plugin-0.17/src/main/java/com/dubture/jenkins/digitalocean/DigitalOceanCloud.java
DigitalOceanCloud digitalOceanCloud = new DigitalOceanCloud(
  cloudParameters.name,
  cloudParameters.authToken,
  cloudParameters.privateKey,
  cloudParameters.sshKeyId,
  cloudParameters.instanceCap,
  cloudParameters.usePrivateNetworking,
  cloudParameters.timeoutMinutes,
  cloudParameters.connectionRetryWait,
  [agentTemplate]
)

// get Jenkins instance
Jenkins jenkins = Jenkins.getInstance()

// add cloud configuration to Jenkins
jenkins.clouds.add(digitalOceanCloud)

// save current Jenkins state to disk
jenkins.save()
```

# Configure plugin via Jenkins Configuration As Code

```yaml
jenkins:
  clouds:
    - digitalOcean:
        authToken: "01234567890123456789012345678901234567890123456789",
        connectionRetryWait: 10
        instanceCap: 5
        name: "mycompany"
        privateKey: |
          ----BEGIN RSA PRIVATE KEY-----
          MIICWwIBAAKBgGuoiHtwl8T2cKfclsWLOcv8S6p74iOAQX1kwCLvLy7ioDFlNzsI
          U235N1StnZYZIwGla+3Uo3jMSUuWkMH85+d3KoRFPS+6RJCiAvMI0hr8FByes22v
          DAVDnhkZ2SFOeh1SPxWDygPo2fW5sgqL2eYLO1CplDdqYhHLAL1FDV5tAgMBAAEC
          gYBWRZoJgXK9zdb9TZIs/6LzSlzAY8IWPOM+PwyRcibXZZiFvNyDm+pviHTEkNRl
          wgMBgLR6xBmz5dEel6utKKQVEPtD1m6N+z6hwUw+Nis35DCvmBX+hQSK+atGgjYH
          ZKz0oqWUSuzHG+CxxcrePDTYJ4fdSyLPsQqaWoCZseDDwQJBANLey9r+juBEQe2N
          MJoZTU1q/AoS5kY7OWQ1aF495I9fz87u9vx8BJh8djvmABwidUWREnd4vwwEIS3M
          JtFGn+kCQQCCsvBvOXgVAlcR54/6ro6R42/0F3bZw0ZFVXvgRRjCZW6m4FyHq4AL
          +gfAV0HERkMdlO1zBpBwkSURekDc9NvlAkAA3zj6k9jlZoLbR50u1fHy4wFdzUw0
          eCQ5nNrsoNbkHOJQGb7dtmmSc9lNUBsqAp53hi0MX2xy0UWN2e1DKkaZAkBi9stH
          7OQYRGVZkVVcI8Cghu7GjN3ZlhsndMsPzkIpMFTQ1yI5OIsEhpZH9co+rFU1mQcT
          Ce1kzwKacU+b/2xhAkEAovqzUMFB9YEbc8C9AzTej5F2ttyuKBDJJ+kvQeJP+PnW
          4ovFI4Ee5UmTWI6k/Md9BM+MvEMWs3nPoF4MULHqNg==
          -----END RSA PRIVATE KEY-----
        sshKeyId: 1234567,
        templates:
          - idleTerminationInMinutes: 10
            imageId: "docker-20-04"
            installMonitoring: false
            instanceCap: 2
            labellessJobsAllowed: false
            name: "docker-20-04"
            numExecutors: 1
            regionId: "tor1"
            setupPrivateNetworking: false
            sizeId: "s-1vcpu-1gb"
            sshPort: 22
            username: "root"
            workspacePath: "/jenkins/"
        timeoutMinutes: 5
        usePrivateNetworking: false
```
