#!/usr/bin/env python3
"""Generate the Xcode project with stable object IDs and the official Google SDK."""
from pathlib import Path
import hashlib
import json
import os

root = Path(__file__).resolve().parents[1]
objects = {}

def oid(name):
    return hashlib.sha1(name.encode()).hexdigest()[:24].upper()

def add(key_name, **fields):
    key = oid(key_name)
    objects[key] = fields
    return key

def quote(value, depth=0):
    indent = '\t' * depth
    if isinstance(value, dict):
        lines = [f'{indent}\t{quote(k)} = {quote(v, depth + 1)};' for k, v in value.items()]
        return '{\n' + '\n'.join(lines) + '\n' + indent + '}'
    if isinstance(value, list):
        return '(' + ', '.join(quote(v, depth) for v in value) + ')'
    return json.dumps(str(value))

# ProductCore, CanvasDrawing, StickerAssets and ProductUI are shared with the Mac app from packages/apple. The
# app compiles their sources directly, as the preview and check scripts do, rather than linking separate modules.
shared = root.parent.parent / 'packages/apple/Sources'
product_core = shared / 'ProductCore'
sources = sorted(root.glob('Sources/**/*.swift')) + [file for target in ['ProductCore', 'CanvasDrawing', 'StickerAssets', 'ProductUI'] for file in sorted((shared / target).glob('*.swift'))]
# The widget extension draws Live Activities. It shares the activity's attributes and the reaction
# types with the app, and nothing else.
widget_only = {s for s in sources if s.is_relative_to(root / 'Sources/Widgets')}
widget_shared = {s for s in sources if s.is_relative_to(root / 'Sources/Shared')} | {product_core / 'Reactions.swift'}
refs, builds, widget_builds = [], [], []
for source in sources:
    relative = os.path.relpath(source, root)
    reference = add(relative, isa='PBXFileReference', lastKnownFileType='sourcecode.swift', path=relative, sourceTree='<group>')
    refs.append(reference)
    if source not in widget_only:
        builds.append(add('build:' + relative, isa='PBXBuildFile', fileRef=reference))
    if source in widget_only or source in widget_shared:
        widget_builds.append(add('widget-build:' + relative, isa='PBXBuildFile', fileRef=reference))
resources = add('resources', isa='PBXFileReference', lastKnownFileType='folder', path='ShortcutTemplates', sourceTree='<group>')
resource_build = add('resources-build', isa='PBXBuildFile', fileRef=resources)
asset_ref = add('assets', isa='PBXFileReference', lastKnownFileType='folder.assetcatalog', path='Resources/Assets.xcassets', sourceTree='<group>')
asset_build = add('assets-build', isa='PBXBuildFile', fileRef=asset_ref)
font_ref = add('fonts', isa='PBXFileReference', lastKnownFileType='folder', path='Resources/Fonts', sourceTree='<group>')
font_build = add('fonts-build', isa='PBXBuildFile', fileRef=font_ref)
legal_ref = add('legal', isa='PBXFileReference', lastKnownFileType='folder', path='Resources/Legal', sourceTree='<group>')
legal_build = add('legal-build', isa='PBXBuildFile', fileRef=legal_ref)
# Each bundle declares its own data use and API reasons.
privacy_ref = add('privacy-manifest', isa='PBXFileReference', lastKnownFileType='text.xml', path='Resources/PrivacyInfo.xcprivacy', sourceTree='<group>')
privacy_build = add('privacy-manifest-build', isa='PBXBuildFile', fileRef=privacy_ref)
widget_privacy_ref = add('widget-privacy-manifest', isa='PBXFileReference', lastKnownFileType='text.xml', path='Resources/Widgets/PrivacyInfo.xcprivacy', sourceTree='<group>')
widget_privacy_build = add('widget-privacy-manifest-build', isa='PBXBuildFile', fileRef=widget_privacy_ref)
icon_refs, icon_builds = [], []
for icon in ['AppIcon', 'AppIconDev']:
    reference = add(icon, isa='PBXFileReference', lastKnownFileType='folder.iconcomposer.icon', path=f'Resources/{icon}.icon', sourceTree='<group>')
    icon_refs.append(reference)
    icon_builds.append(add(icon + '-build', isa='PBXBuildFile', fileRef=reference))
config_ref = add('base-config', isa='PBXFileReference', lastKnownFileType='text.xcconfig', path='Configuration/Base.xcconfig', sourceTree='<group>')
debug_config_ref = add('debug-config', isa='PBXFileReference', lastKnownFileType='text.xcconfig', path='Configuration/Debug.xcconfig', sourceTree='<group>')
google_package = add('google-package', isa='XCRemoteSwiftPackageReference', repositoryURL='https://github.com/google/GoogleSignIn-iOS', requirement={'kind': 'exactVersion', 'version': '9.0.0'})
google_product = add('google-product', isa='XCSwiftPackageProductDependency', package=google_package, productName='GoogleSignIn')
google_build = add('google-build', isa='PBXBuildFile', productRef=google_product)
firebase_package = add('firebase-package', isa='XCRemoteSwiftPackageReference', repositoryURL='https://github.com/firebase/firebase-ios-sdk', requirement={'kind': 'exactVersion', 'version': '12.19.3'})
firebase_product = add('firebase-product', isa='XCSwiftPackageProductDependency', package=firebase_package, productName='FirebaseMessaging')
firebase_build = add('firebase-build', isa='PBXBuildFile', productRef=firebase_product)
crashlytics_product = add('crashlytics-product', isa='XCSwiftPackageProductDependency', package=firebase_package, productName='FirebaseCrashlytics')
crashlytics_build = add('crashlytics-build', isa='PBXBuildFile', productRef=crashlytics_product)
# Uploads the app's symbols so Crashlytics reports read as source, after the Firebase config is copied in.
crashlytics_phase = add('crashlytics-symbols-phase', isa='PBXShellScriptBuildPhase', buildActionMask='2147483647', files=[],
    inputPaths=['${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}',
                '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Resources/DWARF/${PRODUCT_NAME}',
                '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Info.plist',
                '$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/GoogleService-Info.plist',
                '$(TARGET_BUILD_DIR)/$(EXECUTABLE_PATH)'],
    outputPaths=[], runOnlyForDeploymentPostprocessing='0', shellPath='/bin/sh',
    shellScript='"${BUILD_DIR%/Build/*}/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/run"', alwaysOutOfDate='1', name='Upload Crashlytics symbols')
firebase_phase = add('firebase-config-phase', isa='PBXShellScriptBuildPhase', buildActionMask='2147483647', files=[], inputPaths=[], outputPaths=[], runOnlyForDeploymentPostprocessing='0', shellPath='/bin/sh', shellScript='python3 "$SRCROOT/scripts/copy-client-config.py"', alwaysOutOfDate='1', name='Copy public Firebase client configuration')
# Debug builds reach the development server over plain HTTP; the App Store build keeps ATS on. It runs
# after the widget is embedded, which Xcode processes Info.plist after.
local_server_phase = add('local-server-phase', isa='PBXShellScriptBuildPhase', buildActionMask='2147483647', files=[],
    inputPaths=['$(TARGET_BUILD_DIR)/$(INFOPLIST_PATH)'], outputPaths=[], runOnlyForDeploymentPostprocessing='0', shellPath='/bin/sh',
    shellScript='python3 "$SRCROOT/scripts/allow-local-server.py"', alwaysOutOfDate='1', name='Allow the local development server in Debug')
product = add('product', isa='PBXFileReference', explicitFileType='wrapper.application', path='Mulberry.app', sourceTree='BUILT_PRODUCTS_DIR')
widget_product = add('widget-product', isa='PBXFileReference', explicitFileType='wrapper.app-extension', path='MulberryWidgets.appex', sourceTree='BUILT_PRODUCTS_DIR')
widget_assets_ref = add('widget-assets', isa='PBXFileReference', lastKnownFileType='folder.assetcatalog', path='Resources/WidgetAssets.xcassets', sourceTree='<group>')
widget_assets_build = add('widget-assets-build', isa='PBXBuildFile', fileRef=widget_assets_ref)
widget_plist_ref = add('widget-plist', isa='PBXFileReference', lastKnownFileType='text.plist.xml', path='Configuration/Widgets-Info.plist', sourceTree='<group>')
products = add('products', isa='PBXGroup', name='Products', children=[product, widget_product], sourceTree='<group>')
main = add('main', isa='PBXGroup', children=refs + icon_refs + [resources, asset_ref, font_ref, legal_ref, config_ref, debug_config_ref, widget_assets_ref, widget_plist_ref, privacy_ref, widget_privacy_ref, products], sourceTree='<group>')
sources_phase = add('sources-phase', isa='PBXSourcesBuildPhase', buildActionMask='2147483647', files=builds, runOnlyForDeploymentPostprocessing='0')
resources_phase = add('resources-phase', isa='PBXResourcesBuildPhase', buildActionMask='2147483647', files=[resource_build, asset_build, font_build, legal_build, privacy_build] + icon_builds, runOnlyForDeploymentPostprocessing='0')
frameworks_phase = add('frameworks-phase', isa='PBXFrameworksBuildPhase', buildActionMask='2147483647', files=[google_build, firebase_build, crashlytics_build], runOnlyForDeploymentPostprocessing='0')
project_configs, target_configs = [], []
for mode in ['Debug', 'Release']:
    project_configs.append(add('project-' + mode, isa='XCBuildConfiguration', name=mode, buildSettings={
        'SDKROOT': 'iphoneos', 'IPHONEOS_DEPLOYMENT_TARGET': '27.0', 'CLANG_ENABLE_MODULES': 'YES',
        'SWIFT_VERSION': '5.0', 'SWIFT_OPTIMIZATION_LEVEL': '-Onone' if mode == 'Debug' else '-O',
        'DEBUG_INFORMATION_FORMAT': 'dwarf-with-dsym', 'ENABLE_TESTABILITY': 'YES' if mode == 'Debug' else 'NO',
        'SWIFT_ACTIVE_COMPILATION_CONDITIONS': 'DEBUG' if mode == 'Debug' else '',
    }))
    target_configs.append(add('target-' + mode, isa='XCBuildConfiguration', name=mode, baseConfigurationReference=debug_config_ref if mode == 'Debug' else config_ref, buildSettings={
        'PRODUCT_NAME': 'Mulberry', 'PRODUCT_BUNDLE_IDENTIFIER': 'com.subhajit.mulberry.dev' if mode == 'Debug' else 'com.subhajit.mulberry',
        'MULBERRY_DISPLAY_NAME': 'Mulberry',
        'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIconDev' if mode == 'Debug' else 'AppIcon',
        'INFOPLIST_FILE': 'Configuration/Info.plist', 'CODE_SIGN_ENTITLEMENTS': '$(MULBERRY_ENTITLEMENTS_FILE)',
        'CODE_SIGN_STYLE': 'Automatic', 'TARGETED_DEVICE_FAMILY': '1,2', 'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator',
        'SUPPORTS_MACCATALYST': 'NO', 'GENERATE_INFOPLIST_FILE': 'NO', 'ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOL_EXTENSIONS': 'YES',
        # One binary in every build, so the Crashlytics symbol upload waits for its dSYM.
        'ENABLE_DEBUG_DYLIB': 'NO',
    }))
widget_configs = []
for mode in ['Debug', 'Release']:
    widget_configs.append(add('widget-' + mode, isa='XCBuildConfiguration', name=mode, baseConfigurationReference=debug_config_ref if mode == 'Debug' else config_ref, buildSettings={
        'PRODUCT_NAME': 'MulberryWidgets',
        'PRODUCT_BUNDLE_IDENTIFIER': 'com.subhajit.mulberry.dev.widgets' if mode == 'Debug' else 'com.subhajit.mulberry.widgets',
        'INFOPLIST_FILE': 'Configuration/Widgets-Info.plist', 'GENERATE_INFOPLIST_FILE': 'NO',
        'CODE_SIGN_STYLE': 'Automatic', 'TARGETED_DEVICE_FAMILY': '1,2', 'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator',
        'SUPPORTS_MACCATALYST': 'NO', 'SKIP_INSTALL': 'YES', 'APPLICATION_EXTENSION_API_ONLY': 'YES',
        'LD_RUNPATH_SEARCH_PATHS': '$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks',
        'SWIFT_ACTIVE_COMPILATION_CONDITIONS': '$(inherited) WIDGET_EXTENSION' + (' DEBUG' if mode == 'Debug' else ''),
    }))
widget_list = add('widget-configs', isa='XCConfigurationList', buildConfigurations=widget_configs, defaultConfigurationIsVisible='0', defaultConfigurationName='Release')
widget_sources = add('widget-sources-phase', isa='PBXSourcesBuildPhase', buildActionMask='2147483647', files=widget_builds, runOnlyForDeploymentPostprocessing='0')
widget_resources = add('widget-resources-phase', isa='PBXResourcesBuildPhase', buildActionMask='2147483647', files=[widget_assets_build, widget_privacy_build], runOnlyForDeploymentPostprocessing='0')
widget_frameworks = add('widget-frameworks-phase', isa='PBXFrameworksBuildPhase', buildActionMask='2147483647', files=[], runOnlyForDeploymentPostprocessing='0')
widget_target = add('widget-target', isa='PBXNativeTarget', name='MulberryWidgets', productName='MulberryWidgets', productType='com.apple.product-type.app-extension', productReference=widget_product, buildConfigurationList=widget_list, buildPhases=[widget_sources, widget_frameworks, widget_resources], dependencies=[], buildRules=[])
widget_proxy = add('widget-proxy', isa='PBXContainerItemProxy', containerPortal=oid('project'), proxyType='1', remoteGlobalIDString=widget_target, remoteInfo='MulberryWidgets')
widget_dependency = add('widget-dependency', isa='PBXTargetDependency', target=widget_target, targetProxy=widget_proxy)
widget_embed_build = add('widget-embed-build', isa='PBXBuildFile', fileRef=widget_product, settings={'ATTRIBUTES': ['RemoveHeadersOnCopy']})
widget_embed = add('widget-embed-phase', isa='PBXCopyFilesBuildPhase', buildActionMask='2147483647', dstPath='', dstSubfolderSpec='13', files=[widget_embed_build], name='Embed Foundation Extensions', runOnlyForDeploymentPostprocessing='0')
project_list = add('project-configs', isa='XCConfigurationList', buildConfigurations=project_configs, defaultConfigurationIsVisible='0', defaultConfigurationName='Release')
target_list = add('target-configs', isa='XCConfigurationList', buildConfigurations=target_configs, defaultConfigurationIsVisible='0', defaultConfigurationName='Release')
target = add('app-target', isa='PBXNativeTarget', name='Mulberry', productName='Mulberry', productType='com.apple.product-type.application', productReference=product, buildConfigurationList=target_list, buildPhases=[sources_phase, frameworks_phase, resources_phase, firebase_phase, widget_embed, local_server_phase, crashlytics_phase], dependencies=[widget_dependency], buildRules=[], packageProductDependencies=[google_product, firebase_product, crashlytics_product])
project = add('project', isa='PBXProject', attributes={'LastUpgradeCheck': '2710'}, buildConfigurationList=project_list, compatibilityVersion='Xcode 14.0', developmentRegion='en', knownRegions=['en', 'Base'], mainGroup=main, productRefGroup=products, projectDirPath='', projectRoot='', targets=[target, widget_target], packageReferences=[google_package, firebase_package])
folder = root / 'Mulberry.xcodeproj'
folder.mkdir(exist_ok=True)
(folder / 'project.pbxproj').write_text('// !$*UTF8*$!\n' + quote({'archiveVersion': '1', 'classes': {}, 'objectVersion': '56', 'objects': objects, 'rootObject': project}) + '\n')
schemes = folder / 'xcshareddata/xcschemes'
schemes.mkdir(parents=True, exist_ok=True)
ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Mulberry.app" BlueprintName="Mulberry" ReferencedContainer="container:Mulberry.xcodeproj"/>'
(schemes / 'Mulberry.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2620" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print(folder)
