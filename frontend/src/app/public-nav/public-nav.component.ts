import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '@app/core/services/auth.service';
import { PseudonymService } from '@app/core/services/pseudonym.service';
import { UserMenuComponent } from './user-menu/user-menu.component';

/**
 * Shown on public pages (graph, stories).
 * If the user is authenticated → user-menu (name + dropdown).
 * If not → "Login" button.
 */
@Component({
    selector: 'app-public-nav',


    standalone: true,
    imports: [CommonModule, MatButtonModule, MatIconModule, MatTooltipModule, UserMenuComponent],
    templateUrl: './public-nav.component.html',
    styleUrls: ['./public-nav.component.scss'],
})
export class PublicNavComponent {
    auth = inject(AuthService);
    router = inject(Router);
    pseudo = inject(PseudonymService);
}
