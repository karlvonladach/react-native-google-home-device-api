#import "GoogleHomeDeviceApi.h"

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeGoogleHomeDeviceApiSpecJSI>(params);
}

+ (NSString *)moduleName
{
  return @"GoogleHomeDeviceApi";
}

@end
