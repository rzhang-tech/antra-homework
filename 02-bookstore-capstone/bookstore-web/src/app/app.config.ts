import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor } from './api';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    // One interceptor, so no component ever builds an Authorization header by hand. The platform
    // answers 401 wherever an unsigned request arrives, so forgetting the header in one place would
    // produce a failure that reads like a permissions bug rather than a missing line.
    provideHttpClient(withInterceptors([authInterceptor])),
  ]
};
