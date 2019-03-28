%dw 2.0

/**
* Declares a dependency that needs to be downloaded before the scripts gets executed
*/
@AnnotationTarget(targets = ["Import"])
annotation Dependency(artifactId: String)