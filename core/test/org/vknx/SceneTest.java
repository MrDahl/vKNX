package org.vknx;

import static org.vknx.SelfTest.FLAGS_RECEIVE;
import static org.vknx.SelfTest.FLAGS_SEND;
import static org.vknx.SelfTest.FLAGS_STATUS_IN;
import static org.vknx.SelfTest.await;
import static org.vknx.SelfTest.check;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import org.vknx.SelfTest.EtsLikeClient;
import org.vknx.device.DimmerBehavior;
import org.vknx.device.PushButtonBehavior;
import org.vknx.device.SliderPushBehavior;
import org.vknx.device.SwitchActuatorBehavior;
import org.vknx.device.VirtualDevice;
import org.vknx.product.Products;
import org.vknx.sim.Subnet;

/**
 * Scenes and the slider status object, driven the way ETS would (address assignment and full downloads over the bus).
 * Not an ETS compatibility test: it only shows that the simulator behaves as the product definitions describe.
 */
public final class SceneTest {
	private static byte[] write8(final int v) { return new byte[] { 0x00, (byte) 0x80, (byte) v }; }

	private static int[] flags(final int size, final int... at) {
		final int[] f = new int[size];
		for (int i = 0; i < at.length; i += 2)
			f[at[i] - 1] = at[i + 1];
		return f;
	}

	public static void main(final String[] args) throws Exception {
		final var dir = Files.createTempDirectory("vknx-scenetest");
		final List<String> trace = new ArrayList<>();
		final VirtualDevice.Listener listener = (src, cat, text) -> { synchronized (trace) { trace.add(src.name() + " [" + cat + "] " + text); } };
		final Subnet net = Subnet.inProcess();
		final var pb = VirtualDevice.create("pb", Products.PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 1 }, dir.resolve("pb.state"), net, listener);
		final var pb2 = VirtualDevice.create("pb2", Products.SECURE_PUSH_BUTTON, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 5 }, dir.resolve("pb2.state"), net, listener);
		final var sa = VirtualDevice.create("sa", Products.SWITCH_ACTUATOR, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 2 }, dir.resolve("sa.state"), net, listener);
		final var dm = VirtualDevice.create("dm", Products.DIMMER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 3 }, dir.resolve("dm.state"), net, listener);
		final var sl = VirtualDevice.create("sl", Products.PUSH_SLIDER, new byte[] { 0, (byte) 0xFA, 0, 0, 0, 4 }, dir.resolve("sl.state"), net, listener);
		final var etsLink = net.createDeviceLink(new IndividualAddress(0, 2, 255));
		final var ets = new EtsLikeClient(etsLink);
		try {
			int host = 1;
			for (final var d : List.of(pb, pb2, sa, dm, sl)) {
				d.toggleProgrammingMode();
				check("address assigned to " + d.name(), ets.assignAddress(new IndividualAddress(1, 1, host++)));
				Thread.sleep(300);
			}
			final var gaScene = new GroupAddress(7, 0, 1);
			final var gaOut1 = new GroupAddress(7, 0, 2);
			final var gaDimVal = new GroupAddress(7, 0, 3);
			final var gaStat = new GroupAddress(7, 0, 4);

			// ---- scene objects exist and the secure variants share them
			check("push button has the scene objects 21..24", Products.PUSH_BUTTON.object(21).isPresent() && Products.PUSH_BUTTON.object(24).isPresent()
					&& Products.PUSH_BUTTON.object(21).get().sizeBits() == 8 && "DPST-18-1".equals(Products.PUSH_BUTTON.object(21).get().dpt()));
			check("secure push button and secure actuator have the same scene objects",
					Products.SECURE_PUSH_BUTTON.object(24).isPresent() && Products.SECURE_SWITCH_ACTUATOR.object(17).isPresent());
			check("switch actuator scene object is 17, dimmer scene object is 21",
					Products.SWITCH_ACTUATOR.object(17).isPresent() && Products.DIMMER.object(21).isPresent());

			// ---- downloads: key 1 of the push button calls scene 5 at once; two outputs, two channels use scene 5
			final var saParams = Products.SWITCH_ACTUATOR.params("Out1Scene1", 5, "Out1Scene1Value", 1, "Out2Scene1", 5, "Out2Scene1Value", 0,
					"Out3Scene2", 6, "Out3Scene2Value", 1, "Out3Scene3", 8, "Out3Scene3Value", 2, "Out4Scene1", 8, "Out4Scene1Value", 1);
			final var dmParams = Products.DIMMER.params("Ch1Scene1", 5, "Ch1Scene1Value", 40, "Ch2Scene3", 5, "Ch2Scene3Value", 100, "Ch3Scene1", 6, "Ch3Scene1Value", 70,
					"Ch3Scene2", 8, "Ch3Scene2Mode", 1, "Ch3Scene2Value", 10, "Ch4Scene1", 8, "Ch4Scene1Value", 33);
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { gaScene.getRawAddress() }, new int[][] { { 1, 21 } }, flags(24, 21, FLAGS_SEND),
					Products.PUSH_BUTTON.params("Key1Function", 3, "Key1Scene", 5, "Key1SceneStore", 0));
			ets.downloadAll(new IndividualAddress(1, 1, 3), new int[] { gaScene.getRawAddress(), gaOut1.getRawAddress() }, new int[][] { { 1, 17 }, { 2, 1 } },
					flags(17, 17, FLAGS_RECEIVE, 1, FLAGS_RECEIVE), saParams);
			ets.downloadAll(new IndividualAddress(1, 1, 4), new int[] { gaScene.getRawAddress(), gaDimVal.getRawAddress() }, new int[][] { { 1, 21 }, { 2, 3 } },
					flags(21, 21, FLAGS_RECEIVE, 3, FLAGS_RECEIVE), dmParams);
			check("applications loaded", await(() -> pb.applicationLoaded() && sa.applicationLoaded() && dm.applicationLoaded()));
			final var key = (PushButtonBehavior) pb.behavior();
			final var sw = (SwitchActuatorBehavior) sa.behavior();
			final var dim = (DimmerBehavior) dm.behavior();
			check("before any scene: all outputs off, brightness 0", !sw.lampLit(1) && !sw.lampLit(2) && dim.levelPct(0) == 0);
			check("key describes itself as a scene key", String.valueOf(pb.behavior().view().get("d1")).startsWith("Scene 5"));

			// ---- call scene 5
			key.act("press", 1);
			check("scene 5 called: output 1 on (slot 1, value On)", await(() -> sw.lampLit(1)));
			check("scene 5 called: output 2 stays off (slot 1, value Off)", !sw.lampLit(2));
			check("scene 5 called: output 3 not touched (it has scene 6)", !sw.lampLit(3));
			check("scene 5 called: dimmer channel 1 at 40 % (slot 1)", await(() -> dim.levelPct(0) == 40));
			check("scene 5 called: dimmer channel 2 at 100 % (slot 3)", dim.levelPct(1) == 100);
			check("scene 5 called: dimmer channel 3 untouched", dim.levelPct(2) == 0);
			check("actuator view names the last scene", String.valueOf(sa.behavior().view().get("scene")).startsWith("Scene 5 called"));

			// ---- another scene number reaches the other slots only
			etsLink.sendRequestWait(gaScene, Priority.LOW, write8(5)); // scene 6 (number - 1)
			check("scene 6: output 3 on, output 1 unchanged", await(() -> sw.lampLit(3)) && sw.lampLit(1));
			check("scene 6: dimmer channel 3 at 70 %", await(() -> dim.levelPct(2) == 70));

			// ---- "keep the current state/brightness" in a slot: scene 8 leaves output 3 and channel 3 alone, but sets output 4 and channel 4
			etsLink.sendRequestWait(gaScene, Priority.LOW, write8(7));
			check("scene 8: output 4 on (slot says On)", await(() -> sw.lampLit(4)));
			check("scene 8: output 3 keeps its state (slot says Keep)", sw.lampLit(3));
			check("scene 8: channel 4 at 33 %", await(() -> dim.levelPct(3) == 33));
			check("scene 8: channel 3 keeps 70 % (slot says Keep, the stored 10 % is ignored)", dim.levelPct(2) == 70);

			// ---- a scene number nobody has changes nothing
			etsLink.sendRequestWait(gaScene, Priority.LOW, write8(60));
			Thread.sleep(300);
			check("unused scene 61 does nothing", sw.lampLit(1) && !sw.lampLit(2) && dim.levelPct(0) == 40);
			check("unused scene is reported", String.valueOf(sa.behavior().view().get("scene")).contains("no output uses it"));

			// ---- store (learn): change the state, send learn for scene 5, the slots take the current state
			etsLink.sendRequestWait(gaOut1, Priority.LOW, new byte[] { 0x00, (byte) 0x80 }); // output 1 off
			etsLink.sendRequestWait(gaDimVal, Priority.LOW, write8(128)); // channel 1 about 50 %
			check("state changed before storing", await(() -> !sw.lampLit(1) && dim.levelPct(0) == 50));
			etsLink.sendRequestWait(gaScene, Priority.LOW, write8(0x80 | 4)); // store scene 5
			check("store: output 1 slot now holds Off", await(() -> sa.param("Out1Scene1Value") == 0));
			check("store: dimmer channel 1 slot now holds 50 %", await(() -> dm.param("Ch1Scene1Value") == 50));
			check("store: the number of the slot is unchanged", sa.param("Out1Scene1") == 5 && dm.param("Ch1Scene1") == 5);
			check("store: other slots untouched", sa.param("Out3Scene2Value") == 1 && dm.param("Ch2Scene3Value") == 100);
			check("store does not change the outputs", !sw.lampLit(2) && dim.levelPct(0) == 50);
			etsLink.sendRequestWait(gaOut1, Priority.LOW, new byte[] { 0x00, (byte) 0x81 }); // output 1 on again
			key.act("press", 1);
			check("call after store: output 1 follows the stored Off", await(() -> !sw.lampLit(1)));
			check("call after store: dimmer channel 1 at the stored 50 %", dim.levelPct(0) == 50);

			// ---- scene key with long press = store
			ets.downloadAll(new IndividualAddress(1, 1, 1), new int[] { gaScene.getRawAddress() }, new int[][] { { 1, 21 } }, flags(24, 21, FLAGS_SEND),
					Products.PUSH_BUTTON.params("Key1Function", 3, "Key1Scene", 5, "Key1SceneStore", 1));
			check("push button reloaded", await(pb::applicationLoaded));
			etsLink.sendRequestWait(gaOut1, Priority.LOW, new byte[] { 0x00, (byte) 0x81 }); // output 1 on
			check("output 1 on", await(() -> sw.lampLit(1)));
			key.act("down", 1); // short press: the scene is called when the key is released
			Thread.sleep(100);
			check("short press does not call before the key is released", !sw.lampLit(2) && sw.lampLit(1));
			key.act("up", 1);
			check("short press (store enabled) calls the scene on release: output 1 follows the stored Off", await(() -> !sw.lampLit(1)));
			etsLink.sendRequestWait(gaOut1, Priority.LOW, new byte[] { 0x00, (byte) 0x81 });
			check("output 1 on again", await(() -> sw.lampLit(1)));
			key.act("down", 1);
			Thread.sleep(900); // longer than the default 0.6 s
			key.act("up", 1);
			check("long press stores scene 5: output 1 slot now holds On", await(() -> sa.param("Out1Scene1Value") == 1));
			check("long press did not call the scene (output 1 still on)", sw.lampLit(1));

			// ---- the secure push button is the same product behaviour
			final var key2 = (PushButtonBehavior) pb2.behavior();
			ets.downloadAll(new IndividualAddress(1, 1, 2), new int[] { gaScene.getRawAddress() }, new int[][] { { 1, 22 } }, flags(24, 22, FLAGS_SEND),
					Products.SECURE_PUSH_BUTTON.params("Key2Function", 3, "Key2Scene", 6, "Key2SceneStore", 0));
			check("secure push button loaded (plain download; secure commissioning is not part of this test)", await(pb2::applicationLoaded));
			key2.act("press", 2);
			check("secure push button key 2 calls scene 6: output 3 on", await(() -> sw.lampLit(3)));

			// ---- slider: switch status object
			final var slider = (SliderPushBehavior) sl.behavior();
			ets.downloadAll(new IndividualAddress(1, 1, 5), new int[] { gaStat.getRawAddress() }, new int[][] { { 1, 4 } }, flags(4, 4, FLAGS_STATUS_IN),
					Products.PUSH_SLIDER.defaultParams());
			check("slider loaded", await(sl::applicationLoaded));
			check("slider has the switch status object 4 and FollowStatus defaults to Yes",
					Products.PUSH_SLIDER.object(4).isPresent() && sl.param("FollowStatus") == 1);
			etsLink.sendRequestWait(gaStat, Priority.LOW, new byte[] { 0x00, (byte) 0x81 });
			check("switch status ON received: the slider button state is on", await(() -> Boolean.TRUE.equals(slider.view().get("state"))));
			etsLink.sendRequestWait(gaStat, Priority.LOW, new byte[] { 0x00, (byte) 0x80 });
			check("switch status OFF received: the slider button state is off", await(() -> Boolean.FALSE.equals(slider.view().get("state"))));
			ets.downloadAll(new IndividualAddress(1, 1, 5), new int[] { gaStat.getRawAddress() }, new int[][] { { 1, 4 } }, flags(4, 4, FLAGS_STATUS_IN),
					Products.PUSH_SLIDER.params("FollowStatus", 0));
			check("slider reloaded with FollowStatus=No", await(() -> sl.applicationLoaded() && sl.param("FollowStatus") == 0));
			etsLink.sendRequestWait(gaStat, Priority.LOW, new byte[] { 0x00, (byte) 0x81 });
			Thread.sleep(300);
			check("FollowStatus=No: the status is ignored", Boolean.FALSE.equals(slider.view().get("state")));

			// ---- slider scene key: SceneEnable=Yes, scene 5, long press stores
			check("slider scene key is off by default (old downloads show no scene key)", sl.param("SceneEnable") == 0 && Integer.valueOf(0).equals(slider.view().get("sceneKey")));
			ets.downloadAll(new IndividualAddress(1, 1, 5), new int[] { gaScene.getRawAddress() }, new int[][] { { 1, 5 } }, flags(5, 5, FLAGS_SEND),
					Products.PUSH_SLIDER.params("SceneEnable", 1, "Scene", 5, "SceneStore", 1));
			check("slider reloaded with the scene key", await(() -> sl.applicationLoaded() && sl.param("SceneEnable") == 1));
			check("slider card shows the scene key (scene 5)", Integer.valueOf(5).equals(slider.view().get("sceneKey")));
			slider.act("down", 1);
			Thread.sleep(150);
			slider.act("up", 1); // released before the long-press time: call
			check("slider short press calls scene 5 in the actuators", await(() -> String.valueOf(sa.behavior().view().get("scene")).startsWith("Scene 5 called")));
			slider.act("down", 1); // held: stores
			check("slider long press stores scene 5 in the actuators", await(() -> String.valueOf(sa.behavior().view().get("scene")).startsWith("Scene 5 stored")));
			slider.act("up", 1);
			check("the slider reports the last scene it sent", String.valueOf(slider.view().get("scene")).startsWith("Store scene 5"));

			// ---- an application downloaded before version 4 (shorter parameter area) still works, scene slots read as unused
			check("a shorter old parameter area reads the new parameters as defaults",
					Products.SWITCH_ACTUATOR.param("Out1Scene1").read(new byte[161]) == 0);
		}
		catch (final Throwable t) {
			t.printStackTrace(System.out);
			SelfTest.failed++;
		}
		finally {
			if (SelfTest.failed > 0) {
				System.out.println("---- trace ----");
				synchronized (trace) { trace.forEach(System.out::println); }
			}
		}
		System.out.println(SelfTest.failed == 0 ? "SCENE TEST PASSED" : SelfTest.failed + " FAILED");
		System.exit(SelfTest.failed == 0 ? 0 : 1);
	}
}
