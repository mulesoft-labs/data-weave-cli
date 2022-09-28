%dw 2.0
var mavenRepositories = [{
    url: "https://maven.anypoint.mulesoft.com/api/v3/maven"
}]
---
{
  dependencies: [
    {
      kind: "maven",
      artifactId: "data-weave-analytics-library",
      groupId: "68ef9520-24e9-4cf2-b2f5-620025690913",
      version: "1.0.1",
      repositories: mavenRepositories
    }
  ]
}