#!/usr/bin/env bash

mkdir -p /vagrant/configs

pushd /vagrant/contrib/kafka9/vagrant/conf
    tar -cvf /vagrant/dist/configs/heron-mesos-conf.tar.gz mesos
popd