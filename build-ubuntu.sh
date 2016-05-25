#!/usr/bin/env bash

mkdir -p dist/centos

./docker/build-artifacts.sh ubuntu14.04 release dist/ubuntu
