# Security

vKNX is a teaching simulator meant to run on a student's own PC or in a classroom network. It is **not** built to be reachable from the internet.

- The control panel listens on the PC itself only (127.0.0.1).
- The KNXnet/IP interface (UDP/TCP 3671) is open to the local network so that ETS can connect; it uses plain tunnelling without a password, like a simple real interface.
- The keys of the virtual KNX Data Secure devices are stored in the data folder and inside exported `.vknx` files. They protect nothing real; do not reuse them in a real installation.

## Reporting a vulnerability
Please do not open a public issue for something that could harm users (for example a way to run code through an imported file or through the update function). Use **Security → Report a vulnerability** on the GitHub page of the repository (private report), or write to the maintainer through https://marcdahl.dk. You will get an answer as soon as possible; this is a spare-time project.

Only the latest release is maintained.
