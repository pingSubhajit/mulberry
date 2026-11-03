#!/usr/bin/env python3
"""The signed macOS host; the app's modules remain in the local Swift package."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
objects = {}
def oid(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def add(key, **fields):
    objects[oid(key)] = fields
    return oid(key)
def quote(value, depth=0):
    if isinstance(value, dict):
        return '{\n' + '\n'.join('\t'*(depth+1)+f'{quote(k)} = {quote(v, depth+1)};' for k,v in value.items()) + '\n'+'\t'*depth+'}'
    if isinstance(value, list): return '(' + ', '.join(quote(v, depth) for v in value) + ')'
    return json.dumps(str(value))
refs=[]
def file(name, path, kind):
    ref=add(name, isa='PBXFileReference', path=path, lastKnownFileType=kind, sourceTree='<group>')
    refs.append(ref)
    return add(name+'-build', isa='PBXBuildFile', fileRef=ref)
source=file('entry', 'Sources/MulberryMacApp/main.swift', 'sourcecode.swift')
resources=[file('resource-'+p.name, str(p.relative_to(root)), 'folder' if p.is_dir() else 'text.xml' if p.suffix=='.xcprivacy' else 'image.png') for p in sorted((root/'Resources').iterdir()) if p.name!='.DS_Store']
for icon in ['AppIcon','AppIconDev']:
    resources.append(file(icon, f'../ios/Resources/{icon}.icon','folder.iconcomposer.icon'))
# The Time together widget: its views and the anniversary's arithmetic are shared with the iPhone's
# widget extension and compiled straight into this one, which links nothing else.
widget_refs=[]
def widget_file(name, path, kind):
    ref=add('widget-'+name, isa='PBXFileReference', path=path, lastKnownFileType=kind, sourceTree='<group>')
    widget_refs.append(ref)
    return add('widget-'+name+'-build', isa='PBXBuildFile', fileRef=ref)
shared_widget=root.parent.parent/'packages/apple/Widgets/TimeTogether'
widget_sources=[widget_file('bundle','Widgets/MulberryMacWidgets.swift','sourcecode.swift'),
    widget_file('core','../../packages/apple/Sources/ProductCore/TimeTogether.swift','sourcecode.swift')]+[
    widget_file(p.name, str(Path('../..')/p.relative_to(root.parent.parent)), 'sourcecode.swift') for p in sorted(shared_widget.glob('*.swift'))]
widget_resources=[widget_file('assets','../../packages/apple/Widgets/TimeTogether/TimeTogetherAssets.xcassets','folder.assetcatalog'),
    widget_file('privacy','Widgets/PrivacyInfo.xcprivacy','text.xml')]+[
    widget_file(p.name, str(Path('../..')/p.relative_to(root.parent.parent)), 'file') for p in sorted((shared_widget/'Fonts').glob('*.ttf'))]
widget_file('plist','Configuration/Widgets-Info.plist','text.plist.xml')
widget_file('entitlements','Configuration/Widgets.entitlements','text.plist.entitlements')
widget_product=add('widget-product',isa='PBXFileReference',explicitFileType='wrapper.app-extension',path='MulberryWidgets.appex',sourceTree='BUILT_PRODUCTS_DIR')
package=add('package',isa='XCLocalSwiftPackageReference',relativePath='.')
productdep=add('appshell',isa='XCSwiftPackageProductDependency',package=package,productName='AppShell')
framework=add('appshell-build',isa='PBXBuildFile',productRef=productdep)
product=add('product',isa='PBXFileReference',explicitFileType='wrapper.application',path='Mulberry.app',sourceTree='BUILT_PRODUCTS_DIR')
products=add('products',isa='PBXGroup',name='Products',children=[product,widget_product],sourceTree='<group>')
main=add('main',isa='PBXGroup',children=refs+widget_refs+[products],sourceTree='<group>')
sourcephase=add('sources',isa='PBXSourcesBuildPhase',buildActionMask='2147483647',files=[source],runOnlyForDeploymentPostprocessing='0')
resourcephase=add('resources',isa='PBXResourcesBuildPhase',buildActionMask='2147483647',files=resources,runOnlyForDeploymentPostprocessing='0')
frameworkphase=add('frameworks',isa='PBXFrameworksBuildPhase',buildActionMask='2147483647',files=[framework],runOnlyForDeploymentPostprocessing='0')
configphase=add('client-config',isa='PBXShellScriptBuildPhase',buildActionMask='2147483647',files=[],inputPaths=[],outputPaths=[],runOnlyForDeploymentPostprocessing='0',shellPath='/bin/sh',shellScript='python3 "$SRCROOT/Scripts/copy-client-config.py"',alwaysOutOfDate='1',name='Copy selected client configuration')
# Shares iOS's Release-only Crashlytics symbol upload; runs after the Firebase plist is copied in.
symbolsphase=add('crashlytics-symbols',isa='PBXShellScriptBuildPhase',buildActionMask='2147483647',files=[],
    inputPaths=['${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}','${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Resources/DWARF/${PRODUCT_NAME}',
        '${DWARF_DSYM_FOLDER_PATH}/${DWARF_DSYM_FILE_NAME}/Contents/Info.plist','$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/GoogleService-Info.plist','$(TARGET_BUILD_DIR)/$(EXECUTABLE_PATH)'],
    outputPaths=[],runOnlyForDeploymentPostprocessing='0',shellPath='/bin/sh',shellScript='/bin/sh "$SRCROOT/../ios/scripts/upload-crashlytics-symbols.sh"',alwaysOutOfDate='1',name='Upload Crashlytics symbols')
projectconfigs=[]
targetconfigs=[]
widgetconfigs=[]
# Debug and Release builds of both apps sign with the paid team.
TEAM='HMJX9736KN'
for name,env,debug in [('Debug','dev',True),('DevRelease','dev',False),('ProdDebug','prod',True),('Release','prod',False)]:
    projectconfigs.append(add('project-'+name,isa='XCBuildConfiguration',name=name,buildSettings={
        'SDKROOT':'macosx','MACOSX_DEPLOYMENT_TARGET':'26.0','CLANG_ENABLE_MODULES':'YES','SWIFT_VERSION':'6.0',
        'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if debug else '-O','SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG' if debug else '',
        'DEBUG_INFORMATION_FORMAT':'dwarf-with-dsym','ENABLE_TESTABILITY':'YES' if debug else 'NO'}))
    targetconfigs.append(add('target-'+name,isa='XCBuildConfiguration',name=name,buildSettings={
        'PRODUCT_NAME':'Mulberry','PRODUCT_BUNDLE_IDENTIFIER':'com.subhajit.mulberry.beta.dev' if env=='dev' else 'com.subhajit.mulberry.beta',
        'DEVELOPMENT_TEAM':TEAM,'CODE_SIGN_STYLE':'Automatic','CODE_SIGN_ENTITLEMENTS':'Configuration/Release.entitlements',
        'INFOPLIST_FILE':'Configuration/Info.plist','GENERATE_INFOPLIST_FILE':'NO','SUPPORTED_PLATFORMS':'macosx',
        'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIconDev' if env=='dev' else 'AppIcon',
        'MULBERRY_APP_ENVIRONMENT':env,'MULBERRY_REMOTE_PUSH_ENABLED':'YES','MULBERRY_APNS_ENVIRONMENT':'production' if name=='Release' else 'development',
        'ENABLE_APP_SANDBOX':'YES','ENABLE_HARDENED_RUNTIME':'YES','ENABLE_USER_SCRIPT_SANDBOXING':'NO',
        'OTHER_SWIFT_FLAGS':'$(inherited) -parse-as-library','ENABLE_DEBUG_DYLIB':'NO',
        'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/../Frameworks',
        'MULBERRY_APP_GROUP':'$(DEVELOPMENT_TEAM).group.$(PRODUCT_BUNDLE_IDENTIFIER)'}))
    bundle='com.subhajit.mulberry.beta.dev' if env=='dev' else 'com.subhajit.mulberry.beta'
    widgetconfigs.append(add('widget-'+name,isa='XCBuildConfiguration',name=name,buildSettings={
        'PRODUCT_NAME':'MulberryWidgets','PRODUCT_BUNDLE_IDENTIFIER':bundle+'.widgets',
        'DEVELOPMENT_TEAM':TEAM,'CODE_SIGN_STYLE':'Automatic','CODE_SIGN_ENTITLEMENTS':'Configuration/Widgets.entitlements',
        'INFOPLIST_FILE':'Configuration/Widgets-Info.plist','GENERATE_INFOPLIST_FILE':'NO','SUPPORTED_PLATFORMS':'macosx',
        'ENABLE_APP_SANDBOX':'YES','ENABLE_HARDENED_RUNTIME':'YES','SKIP_INSTALL':'YES','APPLICATION_EXTENSION_API_ONLY':'YES',
        'ENABLE_DEBUG_DYLIB':'NO','SWIFT_ACTIVE_COMPILATION_CONDITIONS':'$(inherited) WIDGET_EXTENSION',
        'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/../Frameworks @executable_path/../../../../Frameworks',
        'MULBERRY_APNS_ENVIRONMENT':'production' if name=='Release' else 'development',
        'MULBERRY_APP_GROUP':'$(DEVELOPMENT_TEAM).group.'+bundle}))
projectlist=add('project-configs',isa='XCConfigurationList',buildConfigurations=projectconfigs,defaultConfigurationIsVisible='0',defaultConfigurationName='Release')
targetlist=add('target-configs',isa='XCConfigurationList',buildConfigurations=targetconfigs,defaultConfigurationIsVisible='0',defaultConfigurationName='Release')
widgetlist=add('widget-configs',isa='XCConfigurationList',buildConfigurations=widgetconfigs,defaultConfigurationIsVisible='0',defaultConfigurationName='Release')
widget_target=add('widget-target',isa='PBXNativeTarget',name='MulberryWidgets',productName='MulberryWidgets',productType='com.apple.product-type.app-extension',productReference=widget_product,buildConfigurationList=widgetlist,
    buildPhases=[add('widget-sources',isa='PBXSourcesBuildPhase',buildActionMask='2147483647',files=widget_sources,runOnlyForDeploymentPostprocessing='0'),
        add('widget-frameworks',isa='PBXFrameworksBuildPhase',buildActionMask='2147483647',files=[],runOnlyForDeploymentPostprocessing='0'),
        add('widget-resources',isa='PBXResourcesBuildPhase',buildActionMask='2147483647',files=widget_resources,runOnlyForDeploymentPostprocessing='0')],
    dependencies=[],buildRules=[])
widget_proxy=add('widget-proxy',isa='PBXContainerItemProxy',containerPortal=oid('project'),proxyType='1',remoteGlobalIDString=widget_target,remoteInfo='MulberryWidgets')
widget_dependency=add('widget-dependency',isa='PBXTargetDependency',target=widget_target,targetProxy=widget_proxy)
widget_embed=add('widget-embed',isa='PBXCopyFilesBuildPhase',buildActionMask='2147483647',dstPath='',dstSubfolderSpec='13',
    files=[add('widget-embed-build',isa='PBXBuildFile',fileRef=widget_product,settings={'ATTRIBUTES':['RemoveHeadersOnCopy']})],
    name='Embed Foundation Extensions',runOnlyForDeploymentPostprocessing='0')
target=add('target',isa='PBXNativeTarget',name='Mulberry',productName='Mulberry',productType='com.apple.product-type.application',productReference=product,buildConfigurationList=targetlist,buildPhases=[sourcephase,frameworkphase,resourcephase,configphase,widget_embed,symbolsphase],dependencies=[widget_dependency],buildRules=[],packageProductDependencies=[productdep])
project=add('project',isa='PBXProject',attributes={'LastUpgradeCheck':'2720','TargetAttributes':{target:{'SystemCapabilities':{'com.apple.Push':{'enabled':'1'}}}}},buildConfigurationList=projectlist,compatibilityVersion='Xcode 14.0',developmentRegion='en',knownRegions=['en','Base'],mainGroup=main,productRefGroup=products,projectDirPath='',projectRoot='',targets=[target,widget_target],packageReferences=[package])
folder=root/'MulberryMac.xcodeproj'
folder.mkdir(exist_ok=True)
(folder/'project.pbxproj').write_text('// !$*UTF8*$!\n'+quote({'archiveVersion':'1','classes':{},'objectVersion':'56','objects':objects,'rootObject':project})+'\n')
schemes=folder/'xcshareddata/xcschemes'
schemes.mkdir(parents=True,exist_ok=True)
ref=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Mulberry.app" BlueprintName="Mulberry" ReferencedContainer="container:MulberryMac.xcodeproj"/>'
for name,launch,release in [('Mulberry','Debug','DevRelease'),('Mulberry Production','ProdDebug','Release')]:
    (schemes/f'{name}.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2720" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="{launch}" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="{release}" shouldUseLaunchSchemeArgsEnv="YES" useCustomWorkingDirectory="NO"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="{launch}"/><ArchiveAction buildConfiguration="{release}" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print(folder)
