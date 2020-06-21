package com.wdrbanlist;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class WdrBanlistPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(WdrBanlistPlugin.class);
		RuneLite.main(args);
	}
}