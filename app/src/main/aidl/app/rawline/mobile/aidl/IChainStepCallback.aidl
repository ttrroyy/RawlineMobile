package app.rawline.mobile.aidl;

import app.rawline.mobile.bg.test.ChainStepReport;

// The verdicts of ICoreService.startChainStepTest, delivered once when the test is over (stopped included).
oneway interface IChainStepCallback {
  void onDone(in ChainStepReport report);
}
