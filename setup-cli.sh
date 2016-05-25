#!/usr/bin/env bash

if [[ $# -ne 1 ]] ; then
    echo 'USAGE: ./setup-cli.sh <dist_dir>'
    exit 1
fi

rm -rf $1/heron-cli

mkdir -p $1/heron-cli

tar -vxf $1/heron-client-release-ubuntu14.04.tar.gz -C $1/heron-cli