# Heron stack files

It is possible to run Heron infrastructure using StackDeploy application. Stack files required to run them are 
contained in this directory. Stack files `heron-...-full-...stack` are launching the whole infrastructure from ground up
including Kafka and Exhibitor Mesos frameworks, Kafka brokers, Exhibitor instances. These will also set up Z-nodes and 
make sure the Mesos slaves has all the necessary library dependencies for CentOS 7 machines. Only Zookeeper, Mesos, 
Aurora and StackDeploy app are required on a cluster to run these.

Besides that, in this directory you may also find  `heron-aurora-min-...stack` which you can use with the Vagrant image 
which `vagrant` dir provides for testing out setting up Heron with StackDeploy. The following will help you run it:

## Prerequisites

On a running Vagrant Aurora image one should set up stack deploy app. From the `vagrant` dir run the following:

```
vagrant ssh master -c "./submit-stack-deploy.sh"
```

Other than that you should have the example topologies and Heron dist built, and Heron cli set up. So from the project 
root:

```
./build-ubuntu.sh
./setup-cli-ubuntu.sh
```

Also, compress the files in the cli directory (`dist/ubuntu/heron-0.1.0-SNAPSHOT`) and place the resulting archive in 
the project root.

## Running stack files

Before actual running, specify the StackDeploy API which points to the StackDeploy instance which runs on your Vagrant 
image and add stacks to the stack list. From this dir run, specifying `08` or `09` as a `kafka_version`:

```
export SD_API=http://master:31918
./stack-deploy add --file heron-aurora-min-<kafka_version>.stack
```

Now you may run any of these stacks with the required parameters. So run these:

```
# For Kafka 0.8:
./stack-deploy run heron-aurora-min --var "cluster_name=example" --var "heron_topology_name=<topology_name>" --var \
"source_topic=<source_topic>" --var "target_topic=<target_topic>" --var "kafka_server=<kafka_bootstrap_server>" --var \
"kafka_zk=master:2181" --var "kafka_zk_root=/kafka-08"

# For Kafka 0.9:
./stack-deploy run heron-aurora-min --var "cluster_name=example" --var "heron_topology_name=<topology_name>" --var \
"source_topic=<source_topic>" --var "target_topic=<target_topic>" --var "kafka_server=<kafka_bootstrap_server>"
```

## Verification

Verifying the example topologies running is pretty straightforward if the sample Eeyore application is used. You may 
deploy it using the StackDeploy running this from this directory:

```
./stack-deploy add --file eeyore-min.stack
./stack-deploy run eeyore-min --var "kafka_server=<kafka_bootstrap_server>" --var "source_topic=<source_topic>"
```

You will see the messages mirrored to the target topic by using corresponding Kafka CLI clients. For Kafka 0.8

```
vagrant ssh master

cd kafka-09
tar -zxf kafka_2.10-0.9.0.0.tgz
cd kafka_2.10-0.9.0.0/bin

# You should see your messages when consuming from the target topic here:
./kafka-console-consumer.sh --topic <target_topic> --bootstrap-server <bootstrap_broker> --new-consumer
```

For Kafka 0.9:

```
vagrant ssh master

cd kafka-08
tar -zxf kafka_2.10-0.8.2.2.tgz
cd kafka_2.10-0.8.2.2/bin

# You should see your messages when consuming from the target topic here:
./kafka-console-consumer.sh --zookeeper master:2181/kafka-08 --topic <target_topic>
```

## Shutting the topology down

There are specific stacks provided for killing the topologies which run on Mesos and Aurora frameworks. You may use 
them by running the below commands from this dir. Note however that these stacks are designed for using with clusters,
so in order to use with the Vagrant image provided, you'll need to tweak artifact_urls and set `example` cluster name when
it is needed to do so in the launch_command.

```
./stack-deploy add --file heron-aurora-kill-topology.stack
./stack-deploy run heron-<aurora|mesos>-kill-topology --var "heron_topology_name=<topology_name>"
```

## DCOS stacks
These stacks also deploy the whole Heron infrastructure and showcase deployment of all necessary infrastructure pieces 
required to get example topologies going. There are only Mesos-based stacks for now, as the Aurora not yet supported 
for Elodina DCOS cluster provisioning. Stacks designed to not interfere with any infrastructure pieces already deployed 
and to be easy to clean-out and re-deploy. Although, note that 08 and 09 stacks will conflict between each other if 
deployed on the same cluster. 

**NOTE:** Exhibitor-Mesos framework in these stacks holds its data in the json file. Thus, it is not necessary to 
manually kill any Zookeeper nodes to restart this stack. Although, due to this specification one shouldn't use this 
stack as is in production, Zookeeper should actually be used as a framework storage

```
# leader.mesos:2181 will be used as initial ZK connections for Exhibitor on Mesos 
./stack-deploy add --file dcos-heron-mesos-full-<08|09>.stack
./stack-deploy run heron-mesos-full-dcos --var "heron_topology_name=<topology_name>" --var \
"source_topic=<source_topic>" --var "target_topic=<target_topic>"
```