#!/bin/bash
set -o nounset
set -o errexit

# By default bazel creates files with mode 0555 which means they are only able to be read and not written to. This
# causes cp to fail when trying to overwrite the file. This makes sure that if the file exists we can overwrite it.
function copyFileToDest() {
  if [ -f $2 ]; then
    chmod 755 $2
  fi

  cp $1 $2
}

echo "Building heron with version $HERON_VERSION for platform $TARGET_PLATFORM"

mkdir -p $SCRATCH_DIR
cd $SCRATCH_DIR

echo "Extracting source"
tar -C . -xzf $SOURCE_TARBALL

if [[ "$TARGET_PLATFORM" =~ "ubuntu" ]]; then
  CONFIG_PLATFORM=ubuntu
elif [[ "$TARGET_PLATFORM" =~ "centos" ]]; then
  CONFIG_PLATFORM=centos
elif [[ "$TARGET_PLATFORM" =~ "darwin" ]]; then
  CONFIG_PLATFORM=darwin
else 
  echo "Unknown platform: $TARGET_PLATFORM"
  exit 1
fi

./bazel_configure.py
bazel clean

pack_topologies() {
  echo "Building required topologies and including to /dist"
  bazel build //contrib/kafka9/examples/src/java:all-targets
  mkdir -p $OUTPUT_DIRECTORY/topologies
  cp ./bazel-bin/contrib/kafka9/examples/src/java/*_deploy.jar $OUTPUT_DIRECTORY/topologies
}


pack_release() {
  echo "Creating packages"
  bazel build -c opt --config=$CONFIG_PLATFORM scripts/packages:tarpkgs
  bazel build -c opt --config=$CONFIG_PLATFORM scripts/packages:binpkgs

  echo "Moving packages to /$OUTPUT_DIRECTORY"
  for file in ./bazel-bin/scripts/packages/*.tar.gz; do
    filename=$(basename $file)
    dest=$OUTPUT_DIRECTORY/${filename/.tar/-$HERON_VERSION-$TARGET_PLATFORM.tar}

    copyFileToDest $file $dest
  done

  echo "Moving install scripts to /$OUTPUT_DIRECTORY"
  for file in ./bazel-bin/scripts/packages/*.sh; do
    filename=$(basename $file)
    dest=$OUTPUT_DIRECTORY/${filename/.sh/-$HERON_VERSION-$TARGET_PLATFORM.sh}

    copyFileToDest $file $dest
  done
}

if [ -z $TOPOLOGY_ONLY ]
then
    pack_release
    pack_topologies
else
    echo "TOPOLOGY_ONLY is set. Packing only the topology"
    pack_topologies
fi
