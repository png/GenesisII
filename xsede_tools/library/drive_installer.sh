#!/bin/bash

# Author: Chris Koeritz

if [ -z "$OFFICIAL_DISTRIBUTION_DIR" ]; then
  OFFICIAL_DISTRIBUTION_DIR="http://www.cs.virginia.edu/~mts5x/xsede/2_5_0"
fi
if [ -z "$INSTALLER_TO_RUN" ]; then
  INSTALLER_TO_RUN="linux_container_2_5_0_x64.sh"
fi

export WORKDIR="$( \cd "$(\dirname "$0")" && \pwd )"  # obtain the script's working directory.
cd $WORKDIR

# force the installer to do a console-based login.
unset DISPLAY

if [ -z "$GENII_INSTALL_DIR" -o -z "$TEST_TEMP" -o -z "$XSEDE_TEST_ROOT" ]; then
  echo This script needs to have had the XSEDE test environment loaded already.
  echo It expects the following to be defined:
  echo -e "\tTEST_TEMP\n\tGENII_INSTALL_DIR\n\tXSEDE_TEST_ROOT"
  exit 1
fi

if [ $# -lt 5 ]; then
  echo "This script will download the latest XSEDE installer and attempt to"
  echo "install it.  To do that, it needs these parameters:"
  echo "(1) the grid account that should administrate the newly installed container."
  echo "(2) the password for the grid account in (1)."
  echo "(3) the port on which to run your container."
  echo "(4) the certificate keypair to use for login."
  echo "(5) the password for the keypair."
#  echo "(n) the grid to connect to ('XSEDE' or 'XCG')."
  exit 1
fi

normal_account="$1"; shift
normal_password="$1"; shift
port_num="$1"; shift
keypair_path="$1"; shift
keypair_password="$1"; shift
#grid_name="$1"; shift
grid_name=XSEDE # for now

if [ "$grid_name" != "XSEDE" -a "$grid_name" != "XCG" ]; then
  echo "The grid name provided was not understood.  This can be either:"
  echo -e "\tXSEDE\nor\n\tXCG"
  exit 1
fi

# this is numerical: 1=xcg, 2=xsede.
#grid_choice=2  # assume xsede for the moment.

grid_choice=1  # assume xsede for the moment.
#temporarily only one choice, so its number is '1'.

#if [ "$grid_name" == "XCG" ]; then
#  grid_choice=1
#fi

##############

# start doing some major operations to get this installed.

bash $XSEDE_TEST_ROOT/library/zap_genesis_javas.sh
sleep 2
echo Wiping out any previous version of install.
\rm -rf "$GENII_USER_DIR" "$GENII_INSTALL_DIR"

# set up a place where we can download and run the installer.
playground="$(mktemp -d $TEST_TEMP/installer.XXXXXX)"
# count up any problems during the run.
errors=0

echo "Changing to build area: $playground"
pushd "$playground" &>/dev/null

# first grab latest container installer:
wget "$OFFICIAL_DISTRIBUTION_DIR/$INSTALLER_TO_RUN"

# next install it with a bunch of console interaction...

expect "$XSEDE_TEST_ROOT/library/installation_expecter.tcl" "$playground/$INSTALLER_TO_RUN" "$GENII_INSTALL_DIR" "$normal_account" "$normal_password" "$port_num" "$grid_choice" "$(hostname --fqdn)" "$keypair_path" "$keypair_password"
if [ $? -ne 0 ]; then
  echo An error was reported from the installation expect script.
  ((errors++))
fi

popd &>/dev/null

# clean up the toys now.
\rm -rf "$playground"

# hand back the result we decided.
exit $errors
