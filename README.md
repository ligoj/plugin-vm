# :link: Ligoj VM service plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm) [![Download](https://api.bintray.com/packages/ligoj/maven-repo/plugin-vm/images/download.svg) ](https://bintray.com/ligoj/maven-repo/plugin-vm/_latestVersion)

[![Build Status](https://travis-ci.org/ligoj/plugin-vm.svg?branch=master)](https://travis-ci.org/ligoj/plugin-vm)
[![Build Status](https://circleci.com/gh/ligoj/plugin-vm.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-vm)
[![Build Status](https://codeship.com/projects/a1d42990-0032-0135-86ce-1eedf7dd101e/status?branch=master)](https://codeship.com/projects/212495)
[![Build Status](https://semaphoreci.com/api/v1/ligoj/plugin-vm/branches/master/shields_badge.svg)](https://semaphoreci.com/ligoj/plugin-vm)
[![Build Status](https://ci.appveyor.com/api/projects/status/4u71gndv7yyttei7/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-vm/branch/master)
[![Coverage Status](https://coveralls.io/repos/github/ligoj/plugin-vm/badge.svg?branch=master)](https://coveralls.io/github/ligoj/plugin-vm?branch=master)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-vm)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-vm)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/bc580f38cbcc4dc3be7d2602c8b77fd4)](https://www.codacy.com/app/ligoj/plugin-vm?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-vm&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-vm/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-vm)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) VM service plugin
Provides the following services :
- ON, OFF, REBOOT, RESTART, SUSPEND, RESUME
- Snapshot
- Shcedule operation
- Each plugin implements this service

Dashboard features :
- Status of the VM, including the intermediate busy mode

Implementation plugins
- [vCloud](https://github.com/ligoj/plugin-vm-vcloud)
- [EC2 AWS](https://github.com/ligoj/plugin-vm-aws)
- [GCP](https://github.com/ligoj/plugin-vm-google)
- [Azure](https://github.com/ligoj/plugin-vm-azure)
