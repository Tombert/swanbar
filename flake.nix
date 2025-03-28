{
    description = "Custom swaybar stuff";

    inputs = {
        nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
        flake-utils.url = "github:numtide/flake-utils";
    };

    outputs = { self, nixpkgs, flake-utils}: 
        with flake-utils.lib; eachSystem allSystems (system:
            let
                pkgs = import nixpkgs {
                    inherit system;
                };
            in rec
                { 
                    devShell = pkgs.mkShell {
                        buildInputs = with pkgs; [
			   graalvm-ce
			   clojure
                        ];
                    };

         });
}
