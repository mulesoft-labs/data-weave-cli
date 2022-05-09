#!/bin/bash

source gradle.properties

graal_dir=.graalvm

[[ $(uname -a) =~ Darwin ]] && os=darwin || os=linux

setUpEnvironmentVariables() {
  echo "Setup GRAALVM_HOME and JAVA_HOME environment variables"  
  if [[ $os == darwin ]]
  then
    export GRAALVM_HOME=${PWD}/${graal_dir}/graalvm-ce-java11-${graalvmVersion}/Contents/Home
  else
    export GRAALVM_HOME=${PWD}/${graal_dir}/graalvm-ce-java11-${graalvmVersion}
  fi
  export JAVA_HOME=${GRAALVM_HOME}
}

if [[ ! -d ${graal_dir}/graalvm-ce-java11-${graalvmVersion} ]]
  then
    graalvmDist=graalvm-ce-java11-${os}-amd64-${graalvmVersion}.tar.gz
    echo "Installing GraalVM: ${graalvmDist}"
    mkdir -p ${graal_dir}
    pushd ${graal_dir}
    curl -OL -A "Mozilla Chrome Safari" https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-${graalvmVersion}/${graalvmDist}
    tar xf ${graalvmDist}
    echo $graal_dir
    popd
    setUpEnvironmentVariables
    ${GRAALVM_HOME}/bin/gu install native-image
  else 
    echo "GraalVM already installed"
fi
