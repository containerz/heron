#!/usr/bin/env bash

if [[ $# -ne 1 ]] ; then
    echo 'USAGE: ./setup-cli.sh <dist_dir>'
    exit 1
fi

rm -rf $1/heron-0.12.0

mkdir -p $1/heron-0.12.0

tar -vxf $1/heron-client-0.12.0-ubuntu14.04.tar.gz -C $1/heron-0.12.0

#Re-packing core, so it would contain Mesos and Aurora scheduler jar
rm -rf $1/heron-0.12.0-core

mkdir -p $1/heron-0.12.0-core

tar -vxf $1/heron-core-0.12.0-ubuntu14.04.tar.gz -C $1/heron-0.12.0-core

cp $1/heron-0.12.0/lib/scheduler/* $1/heron-0.12.0-core/heron-core/lib/scheduler

pushd $1/heron-0.12.0-core
    tar -cvf ../heron-core-0.12.0-ubuntu14.04.tar.gz heron-core release.yaml
popd