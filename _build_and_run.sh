#!/bin/bash

java_home_set_ver 11

mkdir -p ./__prebuild__backup
mv ./server-product/target/onedev-11.9.9/conf ./__prebuild__backup/conf
mv ./server-product/target/onedev-11.9.9/internaldb ./__prebuild__backup/internaldb
mv ./server-product/target/onedev-11.9.9/site/projects __prebuild__backup/projects
rm -rf ./server-product/target

if mvn package -Pce
then
	pushd  ./server-product/target/

	unzip -x ./onedev-11.9.9.zip \
	&& rm -rf ./onedev-11.9.9/conf \
	&& rm -rf ./onedev-11.9.9/internaldb \
	&& rm -rf ./onedev-11.9.9/site/projects


	mv ../../__prebuild__backup/conf ./onedev-11.9.9/conf
	mv ../../__prebuild__backup/internaldb ./onedev-11.9.9/internaldb
	mv ../../__prebuild__backup/projects ./onedev-11.9.9/site/projects

	popd
	./server-product/target/onedev-11.9.9/bin/server.sh console
fi

