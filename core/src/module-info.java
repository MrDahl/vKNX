module org.vknx {
	requires io.calimero.core;
	requires io.calimero.device;
	requires io.calimero.server;
	requires jdk.httpserver;
	requires java.desktop;

	exports org.vknx.ui;
	exports org.vknx.product;
	exports org.vknx.device;
	exports org.vknx.sim;
	// Calimero's KNXnet/IP server instantiates the shared virtual line by class name
	opens org.vknx.sim to io.calimero.server;
}
