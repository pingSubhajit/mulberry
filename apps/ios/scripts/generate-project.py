#!/usr/bin/env python3
"""Generate the Xcode project with stable object IDs and the official Google SDK."""
from pathlib import Path
import hashlib
import json

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

# The widget extension draws Live Activities. It shares the activity's attributes and the reaction
# types with the app, and nothing else.
sources = sorted(root.glob('Sources/**/*.swift'))
widget_only = {s for s in sources if s.is_relative_to(root / 'Sources/Widgets')}
widget_shared = {s for s in sources if s.is_relative_to(root / 'Sources/Shared')} | {root / 'Sources/ProductCore/Reactions.swift'}
refs, builds, widget_builds = [], [], []
for source in sources:
    relative = str(source.relative_to(root))
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
firebase_phase = add('firebase-config-phase', isa='PBXShellScriptBuildPhase', buildActionMask='2147483647', files=[], inputPaths=[], outputPaths=[], runOnlyForDeploymentPostprocessing='0', shellPath='/bin/sh', shellScript='python3 "$SRCROOT/scripts/copy-client-config.py"', alwaysOutOfDate='1', name='Copy public Firebase client configuration')
product = add('product', isa='PBXFileReference', explicitFileType='wrapper.application', path='MulberryDelivery.app', sourceTree='BUILT_PRODUCTS_DIR')
widget_product = add('widget-product', isa='PBXFileReference', explicitFileType='wrapper.app-extension', path='MulberryWidgets.appex', sourceTree='BUILT_PRODUCTS_DIR')
widget_assets_ref = add('widget-assets', isa='PBXFileReference', lastKnownFileType='folder.assetcatalog', path='Resources/WidgetAssets.xcassets', sourceTree='<group>')
widget_assets_build = add('widget-assets-build', isa='PBXBuildFile', fileRef=widget_assets_ref)
widget_plist_ref = add('widget-plist', isa='PBXFileReference', lastKnownFileType='text.plist.xml', path='Configuration/Widgets-Info.plist', sourceTree='<group>')
products = add('products', isa='PBXGroup', name='Products', children=[product, widget_product], sourceTree='<group>')
main = add('main', isa='PBXGroup', children=refs + icon_refs + [resources, asset_ref, font_ref, legal_ref, config_ref, debug_config_ref, widget_assets_ref, widget_plist_ref, products], sourceTree='<group>')
sources_phase = add('sources-phase', isa='PBXSourcesBuildPhase', buildActionMask='2147483647', files=builds, runOnlyForDeploymentPostprocessing='0')
resources_phase = add('resources-phase', isa='PBXResourcesBuildPhase', buildActionMask='2147483647', files=[resource_build, asset_build, font_build, legal_build] + icon_builds, runOnlyForDeploymentPostprocessing='0')
frameworks_phase = add('frameworks-phase', isa='PBXFrameworksBuildPhase', buildActionMask='2147483647', files=[google_build, firebase_build], runOnlyForDeploymentPostprocessing='0')
project_configs, target_configs = [], []
for mode in ['Debug', 'Release']:
    project_configs.append(add('project-' + mode, isa='XCBuildConfiguration', name=mode, buildSettings={
        'SDKROOT': 'iphoneos', 'IPHONEOS_DEPLOYMENT_TARGET': '27.0', 'CLANG_ENABLE_MODULES': 'YES',
        'SWIFT_VERSION': '5.0', 'SWIFT_OPTIMIZATION_LEVEL': '-Onone' if mode == 'Debug' else '-O',
        'DEBUG_INFORMATION_FORMAT': 'dwarf', 'ENABLE_TESTABILITY': 'YES' if mode == 'Debug' else 'NO',
        'SWIFT_ACTIVE_COMPILATION_CONDITIONS': 'DEBUG' if mode == 'Debug' else '',
    }))
    target_configs.append(add('target-' + mode, isa='XCBuildConfiguration', name=mode, baseConfigurationReference=debug_config_ref if mode == 'Debug' else config_ref, buildSettings={
        'PRODUCT_NAME': 'MulberryDelivery', 'PRODUCT_BUNDLE_IDENTIFIER': 'com.subhajit.mulberry.dev' if mode == 'Debug' else 'com.subhajit.mulberry',
        'MULBERRY_DISPLAY_NAME': 'Mulberry',
        'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIconDev' if mode == 'Debug' else 'AppIcon',
        'INFOPLIST_FILE': 'Configuration/Info.plist', 'CODE_SIGN_ENTITLEMENTS': '$(MULBERRY_ENTITLEMENTS_FILE)',
        'CODE_SIGN_STYLE': 'Automatic', 'TARGETED_DEVICE_FAMILY': '1,2', 'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator',
        'SUPPORTS_MACCATALYST': 'NO', 'GENERATE_INFOPLIST_FILE': 'NO', 'ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOL_EXTENSIONS': 'YES',
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
widget_resources = add('widget-resources-phase', isa='PBXResourcesBuildPhase', buildActionMask='2147483647', files=[widget_assets_build], runOnlyForDeploymentPostprocessing='0')
widget_frameworks = add('widget-frameworks-phase', isa='PBXFrameworksBuildPhase', buildActionMask='2147483647', files=[], runOnlyForDeploymentPostprocessing='0')
widget_target = add('widget-target', isa='PBXNativeTarget', name='MulberryWidgets', productName='MulberryWidgets', productType='com.apple.product-type.app-extension', productReference=widget_product, buildConfigurationList=widget_list, buildPhases=[widget_sources, widget_frameworks, widget_resources], dependencies=[], buildRules=[])
widget_proxy = add('widget-proxy', isa='PBXContainerItemProxy', containerPortal=oid('project'), proxyType='1', remoteGlobalIDString=widget_target, remoteInfo='MulberryWidgets')
widget_dependency = add('widget-dependency', isa='PBXTargetDependency', target=widget_target, targetProxy=widget_proxy)
widget_embed_build = add('widget-embed-build', isa='PBXBuildFile', fileRef=widget_product, settings={'ATTRIBUTES': ['RemoveHeadersOnCopy']})
widget_embed = add('widget-embed-phase', isa='PBXCopyFilesBuildPhase', buildActionMask='2147483647', dstPath='', dstSubfolderSpec='13', files=[widget_embed_build], name='Embed Foundation Extensions', runOnlyForDeploymentPostprocessing='0')
project_list = add('project-configs', isa='XCConfigurationList', buildConfigurations=project_configs, defaultConfigurationIsVisible='0', defaultConfigurationName='Release')
target_list = add('target-configs', isa='XCConfigurationList', buildConfigurations=target_configs, defaultConfigurationIsVisible='0', defaultConfigurationName='Release')
target = add('app-target', isa='PBXNativeTarget', name='MulberryDelivery', productName='MulberryDelivery', productType='com.apple.product-type.application', productReference=product, buildConfigurationList=target_list, buildPhases=[sources_phase, frameworks_phase, resources_phase, firebase_phase, widget_embed], dependencies=[widget_dependency], buildRules=[], packageProductDependencies=[google_product, firebase_product])
project = add('project', isa='PBXProject', attributes={'LastUpgradeCheck': '2710'}, buildConfigurationList=project_list, compatibilityVersion='Xcode 14.0', developmentRegion='en', knownRegions=['en', 'Base'], mainGroup=main, productRefGroup=products, projectDirPath='', projectRoot='', targets=[target, widget_target], packageReferences=[google_package, firebase_package])
folder = root / 'MulberryDelivery.xcodeproj'
folder.mkdir(exist_ok=True)
(folder / 'project.pbxproj').write_text('// !$*UTF8*$!\n' + quote({'archiveVersion': '1', 'classes': {}, 'objectVersion': '56', 'objects': objects, 'rootObject': project}) + '\n')
schemes = folder / 'xcshareddata/xcschemes'
schemes.mkdir(parents=True, exist_ok=True)
ref = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="MulberryDelivery.app" BlueprintName="MulberryDelivery" ReferencedContainer="container:MulberryDelivery.xcodeproj"/>'
(schemes / 'MulberryDelivery.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2620" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print(folder)
