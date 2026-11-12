# Contributing to vKNX

vKNX is a free teaching tool for KNX, made and maintained by one teacher in his spare time. Help is welcome: bug reports, ideas, and code.

## Reporting a bug or an idea
Use [Issues](https://github.com/MrDahl/vKNX/issues/new/choose). For a bug, the two log files (`vknx.log`, `console.log`, on Windows in `%LOCALAPPDATA%\vKNX\data`) taken right after the problem are worth more than a long description. If you are not sure it is a bug, ask in [Discussions](https://github.com/MrDahl/vKNX/discussions). English or Danish.

## What counts as "works"
A successful build and passing tests do **not** prove that ETS accepts something. Only what has been tried in a real ETS counts as confirmed; `docs/limitations.md` keeps the two apart. If you change a product, say in the pull request whether you tried it in ETS (and which version).

## Building
JDK 21 and git, no Maven or Gradle (see the README):
```
tools/build.sh                 # or tools\build.ps1 on Windows
tools/selftest.sh              # the mini-ETS self-test
tools/selftest.sh ProductsTest # any other test class in core/test/org/vknx
tools/run.sh                   # control panel on http://127.0.0.1:8080/
```

## Rules of the code base
- **`core/src/org/vknx/product/Products.java` is the only place a product is defined.** The ETS product files (`products/*.xml`) are generated from it by the build; do not edit them by hand. A test checks that the checked-in XML matches.
- **A change of a product needs a new application version** (`Products.V`) and new `.knxprod` files. Those can only be made on Windows with ETS (`tools\make-knxprod.ps1`); the maintainer does that. The release stops if the `knxprod` folder does not match `Products.java` (`KnxprodCheck`).
- ETS limits texts to 50 characters; a test enforces it.
- Calimero is fetched at a pinned commit; local changes are small patches in `third_party/patches` with a line in its README saying why.
- The control panel is plain JavaScript and CSS without a build step (`core/resources/web`). Texts in the program are English.
- Add or extend a test in `core/test/org/vknx` for a change in behaviour, add a line to `CHANGELOG.md` under `## [Unreleased]`, and a step in `docs/ui-manual-test.md` if a person has to look at it.

## Pull requests
Keep them small and about one thing. Describe what you tested and what you could not test. By contributing you agree that your contribution is under the Apache License 2.0, like the rest of vKNX.
