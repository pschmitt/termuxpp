# Storage helper parity contract

The guest invokes the Rust implementation in `tpp` as `tpp-setup-storage`. The former shell implementation is retained only as a test oracle.

The parity unit is one invocation: arguments, environment, filesystem state and `am` result in; standard output, standard error, exit status, broadcast arguments and filesystem changes out. Differences in temporary test-directory paths and operating-system error wording are acceptable; differences in exit status, successful output, broadcast arguments, link targets or handling of existing paths are not.

Differential tests must cover help and invalid arguments, missing storage permission and failed broadcasts, a new storage directory, existing correct and conflicting links, dangling symlinks, and a storage directory that is itself a symlink. The shell oracle is not installed in production; the tests compare it with the Rust implementation.
