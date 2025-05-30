#!/bin/bash

target_path="/var/www/onedev-lifecapital"
ver="11.9.9"

# build assumed on same machine
git pull \
&& mvn package -Pce \
&& pushd server-product/target/ \
&& rm -rf onedev-$ver \
&& unzip onedev-$ver.zip \
&& rsync --progress --stats -rltgoD \
  	onedev-$ver/* $target_path \
	--exclude=hibernate.properties \
	--rsync-path="sudo rsync" \
&& popd \
&& sudo systemctl restart onedev-lifecapital && chown -R www-data:www-data $target_path