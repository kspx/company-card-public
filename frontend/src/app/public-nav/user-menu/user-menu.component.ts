import { Component, inject } from '@angular/core';
import { MatFabButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { I18nService, obsToSignal } from '@app/core';
import { OnDestroy$ } from '@cognizone/ng-core';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import {AuthService, User} from "@app/core/services/auth.service";
import {Router} from "@angular/router";

@Component({
  selector: 'app-user-menu',
  standalone: true,
  imports: [MatFabButton, MatMenu, MatMenuTrigger, MatMenuItem, MatIcon, TranslocoDirective],
  template: `
    <ng-container *transloco="let t; prefix: 'ui.user-menu'">
      <button class="my-menu-btn" mat-fab extended [matMenuTriggerFor]="menu"
              [title]="auth.displayName" [attr.aria-label]="t('openUserMenu')">
        {{ initials }}
      </button>
      <mat-menu #menu="matMenu">
        <button mat-menu-item (click)="router.navigate(['/company-card'])">
          <mat-icon>view_list</mat-icon>
          <span>Cards</span>
        </button>
        <button mat-menu-item (click)="router.navigate(['/stories'])">
          <mat-icon>auto_stories</mat-icon>
          <span>Data Stories</span>
        </button>
        @if (auth.role === 'ADMIN') {
          <button mat-menu-item (click)="router.navigate(['/admin'])">
            <mat-icon>admin_panel_settings</mat-icon>
            <span>Admin</span>
          </button>
        }
        <button mat-menu-item [matMenuTriggerFor]="languages">
          <mat-icon>language</mat-icon>
          <span>{{ t('language') }}</span>
        </button>
        <button mat-menu-item (click)="logout()">
          <mat-icon>logout</mat-icon>
          <span>{{ t('logout') }}</span>
        </button>
      </mat-menu>

      <mat-menu #languages="matMenu">
        @for (lang of langs; track $index) {
          <button mat-menu-item (click)="i18nService.setActiveLang(lang)">{{ t('langs.' + lang) }}</button>
        }
      </mat-menu>
    </ng-container>
  `,
  styles: `
    .my-menu-btn {
      --mdc-extended-fab-container-shape: 50%;
      --mat-fab-foreground-color: var(--app-primary-color);
      --mdc-fab-container-color: var(--app-over-primary-color);
      --mdc-extended-fab-container-height: 3rem;
      width: var(--mdc-extended-fab-container-height);
      --mdc-extended-fab-label-text-size: 1.125rem;
      --mdc-extended-fab-label-text-weight: 700;
    }
  `,
})
export class UserMenuComponent extends OnDestroy$ {
  i18nService: I18nService = inject(I18nService);
  auth: AuthService = inject(AuthService);
  langs: string[] = inject(TranslocoService).getAvailableLangs() as string[];
  user = obsToSignal<User | null>(null, this.auth.user$, this.onDestroy$);
  router = inject(Router);


  get initials(): string {
    const name = this.auth.displayName ?? '';
    const parts = name.split(/[.\s_-]+/).filter(Boolean);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return name.slice(0, 2).toUpperCase();
  }

  logout() {
    this.auth.logout().subscribe({ complete: () => void this.router.navigate(['/login']) });
  }
}
