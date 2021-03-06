#!/bin/bash

# stops any running container, wipes out its configuration, and undoes a
# supposedly pre-existing file called bootstrap_save.tar.gz to get a saved
# configuration back.  after that, the container is restarted.
# also the mirror container configuration will be restored if a
# mirror_save.tar.gz file is found.
#
# Author: Chris Koeritz

# standard start-up boilerplate.
export WORKDIR="$( \cd "$(\dirname "$0")" && \pwd )"  # obtain the script's working directory.
cd "$WORKDIR"
export SHOWED_SETTINGS_ALREADY=true
if [ -z "$GFFS_TOOLKIT_SENTINEL" ]; then
  source ../prepare_tools.sh ../prepare_tools.sh 
fi
source "$GFFS_TOOLKIT_ROOT/library/establish_environment.sh"

#hmmm: need to use a variable to refer to location for save file.

if [ ! -f "$TMP/bootstrap_save.tar.gz" ]; then
  echo This script will only succeed if a previous bootstrap quick start was done
  echo and that made a file in $TMP/bootstrap_save.tar.gz to revert from.
  exit 1
fi

if [ -z "$GENII_USER_DIR" -o -z "$GENII_INSTALL_DIR" ]; then
  echo Failed to load in the install dir or user dir somehow.
  exit 1
elif [ ! -d "$GENII_INSTALL_DIR" ]; then
  echo The genesis install directory specified is invalid: $GENII_INSTALL_DIR
  exit 1
fi

# drop running guys.
echo zapping any running genesis javas for this user.
bash "$GFFS_TOOLKIT_ROOT/library/zap_genesis_javas.sh"
if [ $? -ne 0 ]; then echo "===> script failure stopping genesis javas, exiting."; exit 1;  fi
sleep 2
bash "$GFFS_TOOLKIT_ROOT/library/zap_genesis_javas.sh"

echo cleaning out the test output folder.
rm -rf "$TEST_TEMP"
if [ $? -ne 0 ]; then echo "===> script failure cleaning TEST_TEMP, exiting."; exit 1;  fi
mkdir "$TEST_TEMP"

# get saved grid back.
# whack the state directory and logging for whatever was extant.
\rm -rf "$GENII_USER_DIR" "$HOME/.GenesisII"
if [ $? -ne 0 ]; then echo "===> script failure removing user dir, exiting."; exit 1;  fi
if [ ! -z "$BACKUP_USER_DIR" ]; then
  \rm -rf "$BACKUP_USER_DIR"
  if [ $? -ne 0 ]; then echo "===> script failure removing mirror's user dir, exiting."; exit 1;  fi
fi
umask 077


echo restoring saved root container.
bash "$GFFS_TOOLKIT_ROOT/library/restore_container_state.sh" "$TMP/bootstrap_save.tar.gz" 
if [ $? -ne 0 ]; then echo "===> script failure restoring root container state, exiting."; exit 1;  fi

# start container up.
launch_container_if_not_running "$DEPLOYMENT_NAME"
if [ ! -z "$BACKUP_DEPLOYMENT_NAME" -a ! -z "$BACKUP_USER_DIR" ]; then
  save_and_switch_userdir "$BACKUP_USER_DIR"

  echo restoring saved mirror container.
  bash "$GFFS_TOOLKIT_ROOT/library/restore_container_state.sh" "$TMP/mirror_save.tar.gz" 
  if [ $? -ne 0 ]; then echo "===> script failure restoring mirror container state, exiting."; exit 1;  fi

  cp -f "$BACKUP_USER_DIR"/build.container.log4j.properties "$GENII_INSTALL_DIR/lib"

  launch_container_if_not_running "$BACKUP_DEPLOYMENT_NAME"
  restore_userdir
fi

