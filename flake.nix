{
  description = "Custom swaybar stuff";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    with flake-utils.lib;
    eachSystem allSystems (system:
      let
        pkgs = import nixpkgs { inherit system; };
        clojureDeps =
          import ./deps.nix { inherit (pkgs) fetchMavenArtifact fetchgit lib; };
        classp = clojureDeps.makeClasspaths { };
      in rec {
        devShell =
          pkgs.mkShell { buildInputs = with pkgs; [ graalvm-ce clojure ]; };
        packages.default = pkgs.stdenv.mkDerivation {

          name = "swaybar2";
          src = ./.;
          buildInputs = [
            pkgs.clojure
            pkgs.graalvm-ce
            pkgs.unzip
          ];

          preBuild = ''
            echo "FAKE-KEY" > .open-ai-key
          '';

          buildPhase = ''
              runHook preBuild
              export HOME=$TMPDIR
              mkdir -p $TMPDIR/target
              mkdir -p $TMPDIR/classes

               cp -r $src/src $TMPDIR/
               echo "(compile 'swaybar2.core)" > $TMPDIR/compile.clj

              ${pkgs.openjdk}/bin/java \
                -cp ${classp}:$TMPDIR/src \
                -Dclojure.compile.path=$TMPDIR/classes \
                clojure.main $TMPDIR/compile.clj


            mkdir -p $TMPDIR/uberjar-classes

            # Copy compiled classes
            cp -r $TMPDIR/classes/* $TMPDIR/uberjar-classes/
            cp reflect-config.json $TMPDIR
            # Unpack all jars in classpath into the same folder
            for jar in $(echo ${classp} | tr ':' '\n'); do
              if [[ -f "$jar" ]]; then
                unzip -q -o "$jar" -d $TMPDIR/uberjar-classes
              fi
            done
            # Make the uberjar manually
            cd $TMPDIR/uberjar-classes
            echo "Main-Class: swaybar2.core" > $TMPDIR/manifest.txt
            jar cfm $TMPDIR/target/swaybar2.jar  $TMPDIR/manifest.txt .

            # Now compile native image from the jar
            native-image \
              --initialize-at-build-time \
              -jar $TMPDIR/target/swaybar2.jar \
              -H:Name=swaybar2 \
              -H:+ReportExceptionStackTraces \
              -H:ReflectionConfigurationFiles=$TMPDIR/reflect-config.json \
              --no-fallback \
              $TMPDIR/target/swaybar2


              runHook postBuild
          '';
          installPhase = ''
                   mkdir -p $out
                   cp -r $TMPDIR/target/* $out/
		   '';
        };

      });
}
