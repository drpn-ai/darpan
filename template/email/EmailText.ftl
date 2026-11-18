<#-- DAR-BE-315: the text part of an email screen is built in the screen (EmailFrame.textOf, from the same copy blocks as the html), inserted by its text render-mode. The stock macros pad and cut every label at 132 columns and render a fail-widgets branch as nothing, so here the labels and containers say nothing and only the render-mode text appears. -->
<#include "runtime://template/screen-macro/DefaultScreenMacros.text.ftl"/>
<#macro label></#macro>
<#macro container></#macro>
