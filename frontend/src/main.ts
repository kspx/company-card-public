import { bootstrapApplication } from '@angular/platform-browser';
import { ComponentFactoryResolver } from '@angular/core';
import { DomPortalOutlet } from '@angular/cdk/portal';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

// ngx-charts 23 was compiled against CDK 20 whose DomPortalOutlet constructor is
// (element, appRef, injector), but CDK 18 still expects (element, resolver, appRef, injector, doc).
// This means ngx-charts passes ApplicationRef into the resolver slot.
// Detect and correct the swap before resolveComponentFactory is called.
const _origAttach = DomPortalOutlet.prototype.attachComponentPortal;
(DomPortalOutlet.prototype as any).attachComponentPortal = function (portal: any) {
  const self = this as any;
  const resolver = portal.componentFactoryResolver || self._componentFactoryResolver;
  if (resolver && typeof resolver.tick === 'function') {
    // ngx-charts 23 compiled against CDK 20 new signature (element, appRef, injector)
    // but CDK 18 still expects (element, resolver, appRef, injector, doc)
    // — ApplicationRef ended up in the resolver slot, fix it back.
    const actualAppRef = self._componentFactoryResolver;
    const actualInjector = self._appRef;
    self._componentFactoryResolver = actualInjector.get(ComponentFactoryResolver);
    self._appRef = actualAppRef;
    self._defaultInjector = actualInjector;
  }
  return _origAttach.call(self, portal);
};

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
