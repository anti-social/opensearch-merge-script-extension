# FHS environment for building and testing the plugin on NixOS.
#
# OpenSearch testclusters run distribution scripts (bin/opensearch-plugin etc.)
# with a stripped environment, so tools like `dirname` must be available
# at the standard FHS locations (/usr/bin, /bin).
#
# Usage:
#   nix-shell    # interactive shell inside the FHS environment
#
#   # run a single command (nix-shell --run is not supported by FHS envs):
#   $(nix-build shell.nix -A fhs --no-out-link)/bin/opensearch-plugin-env \
#     -c './gradlew --no-daemon integTest'
{ pkgs ? import <nixpkgs> {} }:

let
  jdk = pkgs.jdk21;

  fhs = pkgs.buildFHSEnv {
    name = "opensearch-plugin-env";

    targetPkgs = pkgs: with pkgs; [
      jdk
      bash
      coreutils
      findutils
      gnugrep
      gnused
      gawk
      which
      procps
      util-linux
      git
    ];

    profile = ''
      export JAVA_HOME=${jdk}/lib/openjdk
      # Nix's bash has no default PATH, and testclusters clear the environment
      # for CLI tools, so pass PATH through explicitly (see build.gradle.kts)
      export ORG_GRADLE_PROJECT_testClustersPassPath=true
    '';

    runScript = "bash";
  };
in
fhs.env // { inherit fhs; }
